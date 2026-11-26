package app.vault.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.vault.RELEASES_PAGE
import app.vault.VaultViewModel

@Composable
fun UpdateBanner(viewModel: VaultViewModel) {
    val version = viewModel.availableUpdate ?: return
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text("Version $version is available", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (!openReleasePage(context)) viewModel.message = "No browser found to open the release page" }) { Text("Open") }
            TextButton(onClick = { viewModel.dismissUpdate() }) { Text("Dismiss") }
        }
    }
}

private fun openReleasePage(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
    true
} catch (error: ActivityNotFoundException) {
    false
}
