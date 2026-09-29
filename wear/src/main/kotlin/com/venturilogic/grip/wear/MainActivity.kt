package com.venturilogic.grip.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.venturilogic.grip.ForceMeasurement
import com.venturilogic.grip.wear.ble.LinkState
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

@Composable
private fun App(vm: LiveViewModel = viewModel()) {
    val device by vm.device.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<DeviceKind?>(null) }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val ok = grants[Manifest.permission.BLUETOOTH_SCAN] == true && grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        denied = !ok
        pending?.let { if (ok) vm.connect(it) }
        pending = null
    }

    Scaffold(timeText = { TimeText() }) {
        if (device == null) {
            PickerScreen(denied) { kind ->
                pending = kind
                launcher.launch(BLE_PERMISSIONS)
            }
        } else {
            BackHandler { vm.disconnect() }
            LiveScreen(vm)
        }
    }
}

@Composable
private fun PickerScreen(denied: Boolean, onPick: (DeviceKind) -> Unit) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { Text("Connect", style = MaterialTheme.typography.title3) }
        DeviceKind.entries.forEach { kind ->
            item {
                Chip(
                    label = { Text(kind.label) },
                    onClick = { onPick(kind) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (denied) {
            item {
                Text(
                    "Bluetooth permission is needed to find your device.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.error,
                )
            }
        }
    }
}

@Composable
private fun LiveScreen(vm: LiveViewModel) {
    val m by vm.measurement.collectAsStateWithLifecycle()
    val link by vm.linkState.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()

    // Keep the display on while training; wrist-down shouldn't kill the readout.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(statusLine(link, error, m), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)

        // Tap the number to cycle kg -> lbs -> N.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = vm::cycleUnit),
        ) {
            Text(fmt(m?.current), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(unit.symbol, style = MaterialTheme.typography.caption2)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Peak", fmt(m?.peak))
            Stat("Mean", fmt(m?.mean))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::tare, colors = ButtonDefaults.primaryButtonColors()) {
                Text("Tare", fontSize = 12.sp)
            }
            Button(onClick = vm::resetStats, colors = ButtonDefaults.secondaryButtonColors()) {
                Text("Reset", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.title3)
        Text(label, style = MaterialTheme.typography.caption3)
    }
}

private fun fmt(v: Double?): String = if (v == null) "--" else ((v * 10).roundToInt() / 10.0).toString()

private fun statusLine(link: LinkState, error: String?, m: ForceMeasurement?): String = when {
    error != null -> error
    m?.isTaring == true -> "Taring… hands off"
    link == LinkState.SCANNING -> "Searching…"
    link == LinkState.CONNECTING -> "Connecting…"
    link == LinkState.DISCONNECTED -> "Lost signal"
    link == LinkState.STREAMING && m?.isActive == true -> "Pulling"
    link == LinkState.STREAMING -> m?.samplingRateHz?.let { "Live · $it Hz" } ?: "Live"
    else -> ""
}
