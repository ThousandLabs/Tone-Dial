package com.thousandlabs.tonedial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thousandlabs.tonedial.ui.AppRoot
import com.thousandlabs.tonedial.ui.MainViewModel
import com.thousandlabs.tonedial.ui.TdColors
import com.thousandlabs.tonedial.ui.ToneDialTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.thousandlabs.tonedial.update.Updater.autoCheck()
        if (savedInstanceState == null) vm.onShortcut(intent?.action)
        setContent {
            ToneDialTheme {
                var granted by remember { mutableStateOf(hasAudioPermission()) }
                var askedOnce by remember { mutableStateOf(false) }

                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    granted = result[audioPermission] == true || hasAudioPermission()
                    askedOnce = true
                }

                // Revérifie l'autorisation au retour des paramètres du téléphone
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) granted = hasAudioPermission()
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }

                LaunchedEffect(Unit) {
                    if (!granted) launcher.launch(permissionsToAsk())
                }
                LaunchedEffect(granted) {
                    if (granted) vm.loadLibrary()
                }

                if (granted) {
                    AppRoot(vm)
                } else {
                    PermissionScreen(
                        showSettings = askedOnce,
                        onAsk = { launcher.launch(permissionsToAsk()) },
                        onSettings = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.onShortcut(intent.action)
    }

    override fun onResume() {
        super.onResume()
        com.thousandlabs.tonedial.update.Updater.onAppResumed(this)
    }

    private fun permissionsToAsk(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(audioPermission)
}

@Composable
private fun PermissionScreen(showSettings: Boolean, onAsk: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(TdColors.Background)
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TdColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.LibraryMusic, null, tint = TdColors.Accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Bienvenue sur Tone Dial", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Pour afficher vos musiques, Tone Dial a besoin d'accéder aux fichiers audio du téléphone. Vos musiques restent sur votre appareil.",
            textAlign = TextAlign.Center,
            color = TdColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAsk, colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent)) {
            Text("Autoriser l'accès à la musique")
        }
        if (showSettings) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSettings) { Text("Ouvrir les paramètres de l'appli") }
            Text(
                "Si la fenêtre d'autorisation ne s'affiche plus : Autorisations > Musique et audio > Autoriser.",
                textAlign = TextAlign.Center,
                color = TdColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
