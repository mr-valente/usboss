package com.usboss.host

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by HostRuntime.state.collectAsState()
            LaunchedEffect(Unit) {
                HostRuntime.refreshDevices(this@MainActivity)
            }
            UsbBossScreen(
                state = state,
                onStart = { dispatchServiceAction(this, UsbBossService.ACTION_START) },
                onStop = { dispatchServiceAction(this, UsbBossService.ACTION_STOP) },
                onRefresh = { HostRuntime.refreshDevices(this) },
                onGrant = { HostRuntime.requestPermissions(this) },
            )
        }
    }

    private fun dispatchServiceAction(context: Context, action: String) {
        val intent = UsbBossService.intent(context, action)
        if (action == UsbBossService.ACTION_START) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}

@Composable
private fun UsbBossScreen(
    state: HostUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val background = Brush.linearGradient(
        listOf(Color(0xFF0A0B0D), Color(0xFF141921), Color(0xFF332506)),
    )

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .background(background)
                    .verticalScroll(scrollState)
                    .padding(24.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "USBoss",
                    color = Color(0xFFF4D26A),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Android USB host to Linux controller bridge",
                    color = Color(0xFFE8E8E8),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = state.status,
                    color = Color(0xFFD0D5DD),
                    style = MaterialTheme.typography.bodyLarge,
                )

                ActionRow(onStart = onStart, onStop = onStop, onRefresh = onRefresh, onGrant = onGrant)

                SummaryCard(state)

                if (state.lastError != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF412323)),
                    ) {
                        Text(
                            text = state.lastError,
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFFFFD6D6),
                        )
                    }
                }

                Text(
                    text = "USB Controller Interfaces",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )

                if (state.devices.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x1FFFFFFF)),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("No supported controller interfaces detected.", color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "It is fine to leave the host running before the controller is plugged in. Linux attach mode can stay connected and wait.",
                                color = Color(0xFFD5D9E0),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "USBoss now prefers XInput 360 style interfaces for 8BitDo dongles and still supports HID devices when available.",
                                color = Color(0xFFD5D9E0),
                            )
                        }
                    }
                } else {
                    state.devices.forEach { candidate ->
                        DeviceCard(candidate)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                Text("Start Host")
            }
            Button(onClick = onGrant, modifier = Modifier.weight(1f)) {
                Text("Grant USB")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text("Refresh")
            }
            Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun SummaryCard(state: HostUiState) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Linux connect command", color = Color(0xFFF4D26A), fontWeight = FontWeight.SemiBold)
            Text(
                text = if (state.serverIp.isBlank()) {
                    "usboss-client attach"
                } else {
                    "usboss-client attach --host ${state.serverIp}:${state.tcpPort}"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Discovery UDP port: ${state.discoveryPort}", color = Color(0xFFD5D9E0))
            Text("TCP stream port: ${state.tcpPort}", color = Color(0xFFD5D9E0))
            Text(
                "Connected client(s): ${state.connectedClient ?: "none"}",
                color = Color(0xFFD5D9E0),
            )
            Text(
                "Attach mode on Linux will keep retrying and reconnect automatically.",
                color = Color(0xFF9FA7B5),
            )
            Text(
                "For multiple controllers, run one Linux attach process per device id from `usboss-client list`.",
                color = Color(0xFF9FA7B5),
            )
        }
    }
}

@Composable
private fun DeviceCard(candidate: UsbCandidate) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(candidate.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "${candidate.transportLabel}  VID:PID ${candidate.vendorId.toString(16).padStart(4, '0')}:${candidate.productId.toString(16).padStart(4, '0')}  iface ${candidate.interfaceNumber}",
                color = Color(0xFFD5D9E0),
            )
            Text(
                "Permission: ${if (candidate.hasPermission) "granted" else "needed"}  input ${candidate.inputPacketSize} bytes  output ${candidate.outputPacketSize} bytes",
                color = Color(0xFFD5D9E0),
            )
            Text(candidate.systemPath, color = Color(0xFF9FA7B5))
        }
    }
}
