package com.sim.sishredder

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SiShredderTheme {
                var hasAccess by remember { mutableStateOf(hasAllFilesAccess()) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { hasAccess = hasAllFilesAccess() }
                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { hasAccess = hasAllFilesAccess() }

                if (hasAccess) {
                    BrowserScreen()
                } else {
                    PermissionScreen(
                        onRequest = {
                            if (Build.VERSION.SDK_INT >= 30) {
                                launcher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            } else {
                                permLauncher.launch(
                                    arrayOf(
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
fun SiShredderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFFF5449),
            secondary = androidx.compose.ui.graphics.Color(0xFFE7BDB7),
        ),
        content = content
    )
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Delete, contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(24.dp))
            Text("SiShredder", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "SiShredder permanently destroys files by overwriting them before deletion, " +
                    "so they cannot be recovered.\n\nTo browse and shred your files it needs " +
                    "“All files access”.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Grant access") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel = viewModel()) {
    val dir by vm.currentDir.collectAsState()
    val entries by vm.entries.collectAsState()
    val selection by vm.selection.collectAsState()
    val workState by vm.workState.collectAsState()
    val scheme by vm.scheme.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var confirmShred by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    BackHandler(enabled = dir != vm.root) { vm.navigateUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SiShredder", style = MaterialTheme.typography.titleMedium)
                        Text(
                            dir.path.removePrefix("/storage/emulated/0").ifEmpty { "/" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (dir != vm.root) {
                        IconButton(onClick = { vm.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.selectAll() }) {
                        Icon(Icons.Filled.Checklist, "Select all")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                BottomAppBar {
                    Text(
                        "${selection.size} selected",
                        modifier = Modifier.padding(start = 16.dp).weight(1f),
                    )
                    Button(
                        onClick = { confirmShred = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        Icon(Icons.Filled.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Shred")
                    }
                }
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(entries, key = { it.file.path }) { entry ->
                    EntryRow(
                        entry = entry,
                        selected = entry.file in selection,
                        imageLoader = imageLoader,
                        onClick = {
                            if (entry.isDirectory) vm.navigateTo(entry.file)
                            else vm.toggleSelect(entry.file)
                        },
                        onCheck = { vm.toggleSelect(entry.file) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            scheme = scheme,
            onScheme = vm::setScheme,
            onWipeFreeSpace = { showSettings = false; confirmWipe = true },
            onDismiss = { showSettings = false },
        )
    }

    if (confirmShred) {
        ConfirmDialog(
            title = "Shred ${selection.size} item(s)?",
            text = "The selected files will be overwritten with the " +
                "“${scheme.label}” scheme and permanently destroyed. " +
                "This CANNOT be undone.",
            confirmLabel = "Shred forever",
            onConfirm = { confirmShred = false; vm.shredSelection() },
            onDismiss = { confirmShred = false },
        )
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Wipe free space?",
            text = "All free space on internal shared storage will be filled with random " +
                "data and then released. This destroys remnants of previously deleted " +
                "files. It may take a long time and will temporarily fill your storage.",
            confirmLabel = "Wipe free space",
            onConfirm = { confirmWipe = false; vm.wipeFreeSpace() },
            onDismiss = { confirmWipe = false },
        )
    }

    when (val ws = workState) {
        is WorkState.Shredding -> ProgressDialog(
            title = "Shredding…",
            detail = ws.progress?.let { p ->
                "${p.currentItem}\nFile ${p.itemIndex} of ${p.itemCount} • " +
                    "pass ${p.pass}/${p.passCount}"
            } ?: "Preparing…",
            fraction = ws.progress?.let { p ->
                if (p.bytesTotal > 0) {
                    val perPass = 1f / p.passCount
                    val filePart = (p.pass - 1) * perPass +
                        perPass * (p.bytesDone.toFloat() / p.bytesTotal)
                    ((p.itemIndex - 1) + filePart) / p.itemCount
                } else null
            },
            onCancel = vm::cancelWork,
        )
        is WorkState.WipingFreeSpace -> ProgressDialog(
            title = "Wiping free space…",
            detail = "${formatSize(ws.bytesDone)} of ${formatSize(ws.bytesTotal)} written",
            fraction = if (ws.bytesTotal > 0)
                (ws.bytesDone.toFloat() / ws.bytesTotal).coerceIn(0f, 1f) else null,
            onCancel = vm::cancelWork,
        )
        is WorkState.Done -> AlertDialog(
            onDismissRequest = vm::dismissResult,
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text("OK") } },
            title = { Text("Finished") },
            text = {
                Column {
                    Text(ws.message)
                    ws.failures.take(5).forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
        WorkState.Idle -> {}
    }
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")

@Composable
fun EntryRow(
    entry: Entry,
    selected: Boolean,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onCheck: () -> Unit,
) {
    val ext = entry.file.extension.lowercase()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                entry.isDirectory -> Icon(
                    Icons.Filled.Folder, null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                ext in IMAGE_EXT || ext in VIDEO_EXT -> Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(entry.file)
                            .size(96)
                            .build(),
                        imageLoader = imageLoader,
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
                else -> Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile, null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.isDirectory) "${entry.childCount} item(s)" else formatSize(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = selected, onCheckedChange = { onCheck() })
    }
}

@Composable
fun SettingsDialog(
    scheme: WipeScheme,
    onScheme: (WipeScheme) -> Unit,
    onWipeFreeSpace: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Shredding scheme") },
        text = {
            LazyColumn {
                items(WipeScheme.entries.toList(), key = { it.name }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onScheme(s) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s == scheme, onClick = { onScheme(s) })
                        Column {
                            Text(s.label, fontWeight = FontWeight.Medium)
                            Text(
                                s.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = onWipeFreeSpace) {
                        Icon(Icons.Filled.Warning, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Wipe free space on internal storage")
                    }
                    Text(
                        "Note: on modern flash storage, wear-leveling means no overwrite " +
                            "method is a 100% guarantee. Combining shredding with " +
                            "device encryption (on by default on Android) gives the " +
                            "strongest protection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProgressDialog(title: String, detail: String, fraction: Float?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column {
                Text(detail, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024; i++
    }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
