package com.sim.sishredder

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Entry(
    val file: File,
    val isDirectory: Boolean,
    val size: Long,
    val childCount: Int,
)

sealed class WorkState {
    object Idle : WorkState()
    data class Shredding(val progress: ShredProgress?) : WorkState()
    data class WipingFreeSpace(val bytesDone: Long, val bytesTotal: Long) : WorkState()
    data class Done(val message: String, val failures: List<String>) : WorkState()
}

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val root: File = Environment.getExternalStorageDirectory()

    private val _currentDir = MutableStateFlow(root)
    val currentDir: StateFlow<File> = _currentDir.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _selection = MutableStateFlow<Set<File>>(emptySet())
    val selection: StateFlow<Set<File>> = _selection.asStateFlow()

    private val _workState = MutableStateFlow<WorkState>(WorkState.Idle)
    val workState: StateFlow<WorkState> = _workState.asStateFlow()

    private val _scheme = MutableStateFlow(
        runCatching { WipeScheme.valueOf(prefs.getString("scheme", null) ?: "") }
            .getOrDefault(WipeScheme.RANDOM_1)
    )
    val scheme: StateFlow<WipeScheme> = _scheme.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun setScheme(s: WipeScheme) {
        _scheme.value = s
        prefs.edit().putString("scheme", s.name).apply()
    }

    fun navigateTo(dir: File) {
        _currentDir.value = dir
        _selection.value = emptySet()
        refresh()
    }

    /** @return true if handled (was not already at the root). */
    fun navigateUp(): Boolean {
        val cur = _currentDir.value
        if (cur.canonicalPath == root.canonicalPath) return false
        navigateTo(cur.parentFile ?: root)
        return true
    }

    fun toggleSelect(file: File) {
        _selection.update { if (file in it) it - file else it + file }
    }

    fun selectAll() {
        val all = _entries.value.map { it.file }.toSet()
        _selection.update { if (it.size == all.size) emptySet() else all }
    }

    fun refresh() {
        val dir = _currentDir.value
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                (dir.listFiles() ?: emptyArray())
                    .map {
                        Entry(
                            file = it,
                            isDirectory = it.isDirectory,
                            size = if (it.isFile) it.length() else 0L,
                            childCount = if (it.isDirectory) it.list()?.size ?: 0 else 0,
                        )
                    }
                    .sortedWith(
                        compareByDescending<Entry> { it.isDirectory }
                            .thenBy { it.file.name.lowercase() }
                    )
            }
            _entries.value = list
        }
    }

    fun shredSelection() {
        val targets = _selection.value.toList()
        if (targets.isEmpty()) return
        val passes = _scheme.value.passes
        _workState.value = WorkState.Shredding(null)
        job = viewModelScope.launch {
            val result = try {
                ShredEngine.shred(targets, passes) { p ->
                    _workState.value = WorkState.Shredding(p)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                finishWork("Shredding cancelled.", emptyList())
                throw e
            }
            val msg = if (result.failed.isEmpty())
                "Securely shredded ${result.shredded} file(s)."
            else
                "Shredded ${result.shredded} file(s); ${result.failed.size} failed."
            finishWork(msg, result.failed)
        }
    }

    fun wipeFreeSpace() {
        _workState.value = WorkState.WipingFreeSpace(0, 1)
        job = viewModelScope.launch {
            try {
                ShredEngine.wipeFreeSpace(root) { done, total ->
                    _workState.value = WorkState.WipingFreeSpace(done, total)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                finishWork("Free-space wipe cancelled.", emptyList())
                throw e
            }
            finishWork("Free space wiped.", emptyList())
        }
    }

    fun cancelWork() {
        job?.cancel()
    }

    fun dismissResult() {
        _workState.value = WorkState.Idle
    }

    private fun finishWork(message: String, failures: List<String>) {
        _selection.value = emptySet()
        _workState.value = WorkState.Done(message, failures)
        refresh()
    }
}
