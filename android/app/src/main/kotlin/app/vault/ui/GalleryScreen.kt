package app.vault.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vault.BiometricAuth
import app.vault.UNSORTED
import app.vault.VaultViewModel
import kotlinx.coroutines.delay
import vault.core.MAX_FOLDER_NAME_LENGTH
import vault.core.MediaKind
import vault.core.VaultItem

private const val MESSAGE_VISIBLE_MS = 4_000L
private const val UNSORTED_LABEL = "Unsorted"

private fun folderLabel(folder: String): String = folder.ifEmpty { UNSORTED_LABEL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(viewModel: VaultViewModel, authenticate: BiometricAuth) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val cards = viewModel.folders + if (viewModel.unsortedItems.isNotEmpty()) listOf(UNSORTED) else emptyList()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Vault") }, actions = { VaultActions(viewModel, authenticate) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, "New folder")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncStatus(viewModel)
            TransientMessage(viewModel)
            if (cards.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No folders yet. Tap + to make one.") }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cards, key = { "folder:$it" }) { folder ->
                        FolderCard(
                            viewModel = viewModel,
                            folder = folder,
                            onOpen = { viewModel.openFolder(folder) },
                            onLongPress = { if (folder != UNSORTED) deleting = folder },
                        )
                    }
                }
            }
        }
    }

    if (creating) NewFolderDialog(onDismiss = { creating = false }, onCreate = {
        creating = false
        viewModel.createFolder(it)
    })

    deleting?.let { folder ->
        val count = viewModel.itemsIn(folder).size
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$folder\"?") },
            text = { Text(if (count == 0) "The folder is empty." else "This also permanently deletes the $count file(s) inside it.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteFolder(folder)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    BiometricOffer(viewModel, authenticate)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(viewModel: VaultViewModel, folder: String, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val contents = viewModel.itemsIn(folder)
    val cover = contents.firstOrNull { it.hasThumb }
    val bitmap by produceState<Bitmap?>(null, cover?.id) { value = cover?.let { viewModel.thumbnailFor(it) } }
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) {
                Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(folderLabel(folder).take(1).uppercase(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(folderLabel(folder), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp))
        Text("${contents.size} item(s)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_FOLDER_NAME_LENGTH) },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(viewModel: VaultViewModel, folder: String, authenticate: BiometricAuth) {
    var menuOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = viewModel.selection.isNotEmpty()
    val contents = viewModel.visibleItems

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        viewModel.choosingExternally = false
        viewModel.upload(it)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.choosingExternally = false
        viewModel.upload(it)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${viewModel.selection.size} selected" else folderLabel(folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { viewModel.selection = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") }
                    } else {
                        IconButton(onClick = { viewModel.openFolder(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Folders") }
                    }
                },
                actions = {
                    if (selecting) {
                        Box {
                            TextButton(onClick = { moveOpen = true }) { Text("Move to…") }
                            DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                                viewModel.folders.filter { it != folder }.forEach { target ->
                                    DropdownMenuItem(text = { Text(target) }, onClick = {
                                        moveOpen = false
                                        viewModel.moveSelected(target)
                                    })
                                }
                            }
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    } else {
                        VaultActions(viewModel, authenticate)
                    }
                },
            )
        },
        floatingActionButton = {
            if (folder != UNSORTED) {
                Box {
                    FloatingActionButton(onClick = { menuOpen = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, "Add") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Photos and videos") }, onClick = {
                            menuOpen = false
                            viewModel.choosingExternally = true
                            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        })
                        DropdownMenuItem(text = { Text("Any file") }, onClick = {
                            menuOpen = false
                            viewModel.choosingExternally = true
                            filePicker.launch(arrayOf("*/*"))
                        })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncStatus(viewModel)
            viewModel.adds.forEach { add ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    val status = when {
                        add.failure != null -> "Failed: ${add.failure}"
                        add.finished -> "Added"
                        else -> "Encrypting"
                    }
                    Text("${add.name} · $status", style = MaterialTheme.typography.bodySmall)
                    if (add.failure == null && !add.finished && add.totalBytes > 0) {
                        LinearProgressIndicator(progress = { (add.doneBytes.toFloat() / add.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            TransientMessage(viewModel)
            if (contents.isEmpty()) {
                val hint = if (folder == UNSORTED) "Nothing unsorted." else "Nothing here yet. Tap + to add files."
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(hint) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(contents, key = { it.id }) { item ->
                        Tile(
                            viewModel = viewModel,
                            item = item,
                            selected = item.id in viewModel.selection,
                            onClick = {
                                if (selecting) viewModel.toggleSelection(item.id)
                                else viewModel.viewerIndex = contents.indexOf(item)
                            },
                            onLongClick = { viewModel.toggleSelection(item.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${viewModel.selection.size} item(s)?") },
            text = { Text("This permanently removes them from your server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RowScope.VaultActions(viewModel: VaultViewModel, authenticate: BiometricAuth) {
    var settingsOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { viewModel.syncNow() }) { Icon(Icons.Default.Refresh, "Sync") }
    IconButton(onClick = { viewModel.lock() }) { Icon(Icons.Default.Lock, "Lock") }
    if (viewModel.biometricAvailable()) {
        Box {
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.MoreVert, "Settings") }
            DropdownMenu(expanded = settingsOpen, onDismissRequest = { settingsOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (viewModel.biometricEnabled) "Turn off fingerprint unlock" else "Turn on fingerprint unlock") },
                    onClick = {
                        settingsOpen = false
                        if (viewModel.biometricEnabled) viewModel.disableBiometric() else enableFingerprint(viewModel, authenticate)
                    },
                )
            }
        }
    }
}

private fun enableFingerprint(viewModel: VaultViewModel, authenticate: BiometricAuth) {
    viewModel.prepareBiometricEnrollment()?.let { cipher -> authenticate(cipher, "Turn on fingerprint unlock") { viewModel.completeBiometricEnrollment(it) } }
}

@Composable
private fun BiometricOffer(viewModel: VaultViewModel, authenticate: BiometricAuth) {
    if (!viewModel.offerBiometric) return
    AlertDialog(
        onDismissRequest = { viewModel.declineBiometric() },
        title = { Text("Unlock with fingerprint?") },
        text = { Text("Next time you can open Vault with your fingerprint instead of typing your passkey. Your passkey still works.") },
        confirmButton = { TextButton(onClick = { enableFingerprint(viewModel, authenticate) }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = { viewModel.declineBiometric() }) { Text("Not now") } },
    )
}

@Composable
private fun TransientMessage(viewModel: VaultViewModel) {
    LaunchedEffect(viewModel.message) {
        if (viewModel.message != null) {
            delay(MESSAGE_VISIBLE_MS)
            viewModel.message = null
        }
    }
    viewModel.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun ShareFolderPicker(viewModel: VaultViewModel) {
    if (viewModel.shareAwaitingFolder.isEmpty()) return
    AlertDialog(
        onDismissRequest = { viewModel.saveSharedTo(null) },
        title = { Text("Save ${viewModel.shareAwaitingFolder.size} item(s) to…") },
        text = {
            Column {
                if (viewModel.folders.isEmpty()) Text("Make a folder first.")
                viewModel.folders.forEach { folder ->
                    TextButton(onClick = { viewModel.saveSharedTo(folder) }, modifier = Modifier.fillMaxWidth()) { Text(folder) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { viewModel.saveSharedTo(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun SyncStatus(viewModel: VaultViewModel) {
    val pending = viewModel.pendingCount
    val text = when {
        viewModel.offline && pending > 0 -> "Offline · $pending waiting to upload"
        viewModel.offline -> "Offline · showing the copy on this phone"
        viewModel.syncing -> "Syncing…"
        pending > 0 -> "$pending waiting to upload"
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(viewModel: VaultViewModel, item: VaultItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, item.id) { value = viewModel.thumbnailFor(item) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val thumbnail = bitmap
        if (thumbnail != null) {
            Image(thumbnail.asImageBitmap(), contentDescription = item.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(item.meta.name.substringAfterLast('.', "file").take(5).uppercase(), style = MaterialTheme.typography.labelLarge)
        }
        if (item.pendingUpload) {
            Text(
                "Waiting to upload",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA000000)).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (item.meta.kind == MediaKind.VIDEO) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(24.dp))
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.TopEnd) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(6.dp).size(24.dp))
            }
        }
    }
}
