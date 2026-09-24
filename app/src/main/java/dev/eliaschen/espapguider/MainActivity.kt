package dev.eliaschen.espapguider

import android.bluetooth.BluetoothProfile
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.eliaschen.espapguider.ui.theme.ESPAPGuiderTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

val LocalBLT = compositionLocalOf<BLTService> { error("BLTService not provided") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bluetoothService = BLTService(this)
        setContent {
            ESPAPGuiderTheme {
                val permissionsLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                        if (result.values.all { it }) {
                            bluetoothService.startListening()
                        }
                    }
                val connectionState by bluetoothService.connectionState.collectAsState()
                var showDashboard by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    permissionsLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                    onDispose {
                        bluetoothService.stopListening()
                    }
                }

                LaunchedEffect(connectionState) {
                    delay(1.seconds)
                    showDashboard = connectionState == BluetoothProfile.STATE_CONNECTED
                }

                CompositionLocalProvider(LocalBLT provides bluetoothService) {
                    AnimatedContent(showDashboard,) {
                        if (it) {
                            Dashboard()
                        } else {
                            Guider()
                        }
                    }
                }
            }
        }
    }
}