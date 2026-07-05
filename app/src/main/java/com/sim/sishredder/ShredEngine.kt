package com.sim.sishredder

import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * One overwrite pass: either a fixed byte pattern or cryptographically random data.
 */
sealed class Pass {
    data class Pattern(val bytes: ByteArray) : Pass() {
        constructor(b: Int) : this(byteArrayOf(b.toByte()))
    }
    object Random : Pass()
}

// A single overwrite pass is sufficient on flash storage: if the write lands
// in place the data is gone; if the storage remaps it (f2fs, FTL), additional
// passes land in the same wrong place. Multi-pass standards add nothing here.
enum class WipeScheme(val label: String, val description: String, val passes: List<Pass>) {
    RANDOM_1(
        "Random",
        "Overwrite once with random data. Correct on every kind of storage, " +
            "including unencrypted SD cards.",
        listOf(Pass.Random)
    ),
    ZERO_1(
        "Zeros (fast)",
        "Overwrite once with zeros. Equivalent on encrypted internal storage, " +
            "faster for bulk jobs.",
        listOf(Pass.Pattern(0x00))
    );
}

data class ShredProgress(
    val currentItem: String,
    val itemIndex: Int,
    val itemCount: Int,
    val pass: Int,
    val passCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
)

data class ShredResult(val shredded: Int, val failed: List<String>)

/**
 * Securely destroys files by overwriting their contents in place, truncating,
 * renaming to a random name, and finally deleting. Data is flushed to disk
 * (fsync) after every pass so the overwrite actually reaches storage.
 */
object ShredEngine {

    private const val BUFFER_SIZE = 1 shl 20 // 1 MiB
    private val random = SecureRandom()

    suspend fun shred(
        targets: List<File>,
        passes: List<Pass>,
        onProgress: (ShredProgress) -> Unit,
    ): ShredResult = withContext(Dispatchers.IO) {
        // Expand folders into their files, deepest first so dirs empty out.
        val files = mutableListOf<File>()
        val dirs = mutableListOf<File>()
        for (t in targets) collect(t, files, dirs)

        val failed = mutableListOf<String>()
        var shredded = 0
        files.forEachIndexed { index, file ->
            coroutineContext.ensureActive()
            try {
                shredFile(file, passes) { pass, done, total ->
                    onProgress(
                        ShredProgress(
                            currentItem = file.name,
                            itemIndex = index + 1,
                            itemCount = files.size,
                            pass = pass,
                            passCount = passes.size,
                            bytesDone = done,
                            bytesTotal = total,
                        )
                    )
                }
                shredded++
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed += "${file.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        // Remove now-empty directories, deepest paths first.
        dirs.sortedByDescending { it.path.length }.forEach { it.delete() }
        ShredResult(shredded, failed)
    }

    private fun collect(target: File, files: MutableList<File>, dirs: MutableList<File>) {
        if (target.isDirectory) {
            dirs += target
            target.listFiles()?.forEach { collect(it, files, dirs) }
        } else {
            files += target
        }
    }

    private suspend fun shredFile(
        file: File,
        passes: List<Pass>,
        onProgress: (pass: Int, done: Long, total: Long) -> Unit,
    ) {
        val length = file.length()
        if (length > 0) {
            RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                val buffer = ByteArray(BUFFER_SIZE)
                passes.forEachIndexed { passIndex, pass ->
                    raf.seek(0)
                    if (pass is Pass.Pattern) fillPattern(buffer, pass.bytes)
                    var written = 0L
                    while (written < length) {
                        coroutineContext.ensureActive()
                        if (pass is Pass.Random) random.nextBytes(buffer)
                        val chunk = minOf(BUFFER_SIZE.toLong(), length - written).toInt()
                        raf.write(buffer, 0, chunk)
                        written += chunk
                        onProgress(passIndex + 1, written, length)
                    }
                    channel.force(true)
                }
                raf.setLength(0)
                channel.force(true)
            }
        }
        // Obscure the original name and size in directory metadata before unlinking.
        var current = file
        val renamed = File(file.parentFile, randomName())
        if (current.renameTo(renamed)) current = renamed
        if (!current.delete()) throw java.io.IOException("could not delete")
    }

    private fun fillPattern(buffer: ByteArray, pattern: ByteArray) {
        for (i in buffer.indices) buffer[i] = pattern[i % pattern.size]
    }

    private fun randomName(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * Fills the free space of the volume containing [dir] with random junk
     * files, fsyncs them, then deletes them, destroying remnants of
     * previously deleted files that still sit in unallocated space.
     */
    suspend fun wipeFreeSpace(
        dir: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val wipeDir = File(dir, ".sishredder_wipe").apply { mkdirs() }
        val buffer = ByteArray(BUFFER_SIZE)
        val total = StatFs(dir.path).availableBytes
        var done = 0L
        try {
            var fileIndex = 0
            // Keep a small safety margin so the OS stays functional.
            val margin = 32L shl 20
            while (StatFs(dir.path).availableBytes > margin) {
                val junk = File(wipeDir, "junk_${fileIndex++}")
                RandomAccessFile(junk, "rw").use { raf ->
                    val target = minOf(256L shl 20, StatFs(dir.path).availableBytes - margin)
                    var written = 0L
                    while (written < target) {
                        coroutineContext.ensureActive()
                        random.nextBytes(buffer)
                        val chunk = minOf(BUFFER_SIZE.toLong(), target - written).toInt()
                        try {
                            raf.write(buffer, 0, chunk)
                        } catch (e: java.io.IOException) {
                            break // volume genuinely full
                        }
                        written += chunk
                        done += chunk
                        onProgress(done, total)
                    }
                    raf.channel.force(true)
                }
            }
        } finally {
            wipeDir.listFiles()?.forEach { it.delete() }
            wipeDir.delete()
        }
    }
}
