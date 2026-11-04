package app.vault

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import app.vault.ui.FolderScreen
import app.vault.ui.FoldersScreen
import app.vault.ui.ShareFolderPicker
import app.vault.ui.UnlockScreen
import app.vault.ui.VaultTheme
import app.vault.ui.ViewerScreen
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

typealias BiometricAuth = (cipher: Cipher, title: String, onSuccess: (Cipher) -> Unit) -> Unit

class MainActivity : FragmentActivity() {
    private val viewModel: VaultViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            VaultTheme {
                VaultApp(viewModel, ::authenticate)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart() {
        super.onStart()
        viewModel.appStarted()
    }

    override fun onStop() {
        super.onStop()
        viewModel.appStopped()
    }

    private fun authenticate(cipher: Cipher, title: String, onSuccess: (Cipher) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess)
            }
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Use passkey")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        viewModel.upload(uris)
    }
}

@Composable
private fun VaultApp(viewModel: VaultViewModel, authenticate: BiometricAuth) {
    val viewing = viewModel.viewerIndex
    val folder = viewModel.currentFolder
    BackHandler(enabled = viewing != null || viewModel.selection.isNotEmpty() || folder != null) {
        when {
            viewing != null -> viewModel.viewerIndex = null
            viewModel.selection.isNotEmpty() -> viewModel.selection = emptySet()
            else -> viewModel.openFolder(null)
        }
    }
    when {
        !viewModel.unlocked -> UnlockScreen(viewModel, authenticate)
        viewing != null -> ViewerScreen(viewModel, viewing)
        folder != null -> FolderScreen(viewModel, folder, authenticate)
        else -> FoldersScreen(viewModel, authenticate)
    }
    if (viewModel.unlocked) ShareFolderPicker(viewModel)
}
