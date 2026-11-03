package app.vault.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.vault.VaultViewModel
import app.vault.media.VaultDataSource
import vault.core.MediaKind
import vault.core.VaultItem

private val ReplayIcon: ImageVector = materialIcon(name = "Vault.Replay") {
    materialPath {
        moveTo(7f, 7f); horizontalLineToRelative(10f); verticalLineToRelative(3f); lineToRelative(4f, -4f); lineToRelative(-4f, -4f)
        verticalLineToRelative(3f); horizontalLineTo(5f); verticalLineToRelative(6f); horizontalLineToRelative(2f); close()
        moveTo(17f, 17f); horizontalLineTo(7f); verticalLineToRelative(-3f); lineToRelative(-4f, 4f); lineToRelative(4f, 4f)
        verticalLineToRelative(-3f); horizontalLineToRelative(12f); verticalLineToRelative(-6f); horizontalLineToRelative(-2f); close()
    }
}

@Composable
fun ViewerScreen(viewModel: VaultViewModel, startIndex: Int) {
    val items = viewModel.visibleItems
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<VaultItem?>(null) }
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(SaveDocument()) { destination ->
        viewModel.choosingExternally = false
        val target = saving
        saving = null
        if (destination != null && target != null) viewModel.saveTo(target, destination)
    }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].id }) { page ->
            val item = items[page]
            when (item.meta.kind) {
                MediaKind.IMAGE -> ImagePage(viewModel, item)
                MediaKind.VIDEO -> VideoPage(viewModel, item, active = page == pager.settledPage)
                MediaKind.OTHER -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("${item.meta.name}\nNo preview. Use save to export it.", color = Color.White)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().background(Color(0x66000000)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { viewModel.viewerIndex = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(current.meta.name, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            if (current.meta.kind == MediaKind.VIDEO) {
                IconButton(onClick = { viewModel.toggleReplayVideos() }) {
                    Icon(
                        ReplayIcon,
                        if (viewModel.replayVideos) "Replay videos: on" else "Replay videos: off",
                        tint = if (viewModel.replayVideos) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
            }
            IconButton(onClick = {
                saving = current
                viewModel.choosingExternally = true
                saver.launch(SaveRequest(current.meta.name, current.meta.mimeType))
            }) { Icon(Icons.Default.Share, "Save to device", tint = Color.White) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = Color.White) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this file?") },
            text = { Text("This permanently removes it from your server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(listOf(current))
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let { text ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            viewModel.message = null
        }
    }
}

@Composable
private fun ImagePage(viewModel: VaultViewModel, item: VaultItem) {
    val state by produceState<Result<Bitmap?>?>(null, item.id) {
        value = runCatching { viewModel.loadImage(item) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val loaded = state
        when {
            loaded == null -> CircularProgressIndicator()
            loaded.getOrNull() != null -> Image(loaded.getOrThrow()!!.asImageBitmap(), item.meta.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else -> Text("Cannot display this image. Use save to export it.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(viewModel: VaultViewModel, item: VaultItem, active: Boolean) {
    val context = LocalContext.current
    var failed by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VaultDataSource.Factory { id -> viewModel.readerFor(id) }))
            .build()
            .apply {
                setMediaItem(MediaItem.Builder().setUri("vault://${item.id}").setMimeType(item.meta.mimeType).build())
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Vault", "playback of ${item.id} failed", error)
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(player, viewModel.replayVideos) {
        player.repeatMode = if (viewModel.replayVideos) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        onDispose { }
    }
    DisposableEffect(active) {
        player.playWhenReady = active
        if (!active) player.pause()
        onDispose { }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)).clickable {
                    failed = false
                    player.prepare()
                },
                contentAlignment = Alignment.Center,
            ) {
                Text("Couldn't load this video. Tap to retry", color = Color.White)
            }
        }
    }
}
