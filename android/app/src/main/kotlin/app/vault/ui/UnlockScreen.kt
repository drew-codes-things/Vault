package app.vault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.vault.BiometricAuth
import app.vault.VaultViewModel

@Composable
fun UnlockScreen(viewModel: VaultViewModel, authenticate: BiometricAuth) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var setupToken by rememberSaveable { mutableStateOf("") }
    var firstTime by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(viewModel.serverUrl) }
    var editingServer by rememberSaveable { mutableStateOf(viewModel.serverConfigurable && viewModel.serverUrl.isEmpty()) }
    val passkeyFocus = remember { FocusRequester() }
    val unlockWithFingerprint = {
        viewModel.biometricUnlockCipher()?.let { cipher -> authenticate(cipher, "Unlock Vault") { viewModel.unlockWithBiometric(it) } }
    }
    LaunchedEffect(Unit) {
        passkeyFocus.requestFocus()
        if (viewModel.biometricEnabled) unlockWithFingerprint()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("Vault", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        if (editingServer) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server address") },
                placeholder = { Text("https://vault.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (viewModel.hasOfflineCopy) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Switching server removes this phone's offline copy of your current vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
        } else if (viewModel.serverConfigurable) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Server: ${viewModel.serverUrl.substringAfter("://")}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !viewModel.busy, onClick = { editingServer = true }) { Text("Change") }
            }
            Spacer(Modifier.height(4.dp))
        }

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passkey") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().focusRequester(passkeyFocus),
        )
        if (firstTime) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                label = { Text("Confirm passkey") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = setupToken,
                onValueChange = { setupToken = it },
                label = { Text("Setup token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If you forget this passkey your files cannot be recovered. Nobody, including the server, can reset it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(value = firstTime, role = Role.Switch, onValueChange = { firstTime = it }),
        ) {
            Text("First-time setup", modifier = Modifier.weight(1f))
            Switch(checked = firstTime, onCheckedChange = null)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !viewModel.busy && passphrase.isNotEmpty() && (!editingServer || server.isNotBlank()),
            onClick = {
                viewModel.unlock(
                    passphrase.toCharArray(),
                    if (firstTime) setupToken.trim() else null,
                    if (firstTime) confirmation.toCharArray() else null,
                    if (editingServer) server else null,
                )
                passphrase = ""
                confirmation = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (viewModel.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (firstTime) "Create vault" else "Unlock")
        }
        if (!firstTime && !editingServer && viewModel.biometricEnabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = !viewModel.busy, onClick = { unlockWithFingerprint() }, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock with fingerprint")
            }
        }
        if (!firstTime && viewModel.hasOfflineCopy) {
            Spacer(Modifier.height(8.dp))
            Text("Works offline. Changes sync when you're back online.", style = MaterialTheme.typography.bodySmall)
        }
        viewModel.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
