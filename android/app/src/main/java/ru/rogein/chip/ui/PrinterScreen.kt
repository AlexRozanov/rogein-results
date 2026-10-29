package ru.rogein.chip.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import ru.rogein.chip.printer.BondedPrinter

private val CardWhite = Color.White
private val Muted = Color(0xFF5C5C5C)
private val OkGreen = Color(0xFF1B4D3E)

@Composable
fun PrinterScreen(
    selectedName: String,
    onSelect: (BondedPrinter) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
    loadBonded: () -> List<BondedPrinter>,
) {
    val context = LocalContext.current
    fun bluetoothGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(bluetoothGranted()) }
    var devices by remember { mutableStateOf(emptyList<BondedPrinter>()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = bluetoothGranted()
        if (granted) devices = loadBonded()
    }
    LaunchedEffect(granted) {
        if (granted) devices = loadBonded()
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Сначала сопрягите XP-P502A в настройках Bluetooth телефона, затем выберите его здесь.",
                color = Muted,
            )
        }
        item {
            Text(
                "Самотест: выключите принтер, зажмите FEED, включите и через 2 секунды отпустите. Если лист пустой — переверните рулон: поскребите бумагу ногтем, тёмная сторона должна смотреть на печатающую голову в крышке.",
                color = Muted,
            )
        }
        item {
            Text(
                if (selectedName.isBlank()) "Принтер не выбран" else "Сейчас: $selectedName",
                color = if (selectedName.isBlank()) Muted else OkGreen,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (!granted) {
            item {
                Button(
                    onClick = {
                        launcher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Разрешить Bluetooth") }
            }
        } else {
            if (selectedName.isNotBlank()) {
                item {
                    Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                        Text("Проверить печать")
                    }
                }
                item {
                    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                        Text("Отключить")
                    }
                }
            }
            if (devices.isEmpty()) {
                item {
                    Text("Нет сопряжённых устройств. Откройте настройки Bluetooth телефона и подключите принтер.", color = Muted)
                }
            }
            items(devices, key = { it.address }) { device ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(device) },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(device.address, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
