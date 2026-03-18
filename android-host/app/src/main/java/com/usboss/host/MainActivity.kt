package com.usboss.host

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private object UsbBossPalette {
    val canvas = Color(0xFF0D0815)
    val canvasRaised = Color(0xFF181024)
    val surface = Color(0xFF1B1229)
    val surfaceRaised = Color(0xFF241634)
    val surfaceMuted = Color(0xFF2B1C3C)
    val violet = Color(0xFF5F34C8)
    val orchid = Color(0xFF8A56D8)
    val pink = Color(0xFFD68BF2)
    val textPrimary = Color(0xFFF5EEFF)
    val textSecondary = Color(0xFFD4C4E6)
    val textMuted = Color(0xFF9F8DB6)
    val success = Color(0xFF83F0C0)
    val warning = Color(0xFFF5C978)
    val danger = Color(0xFFFF96B2)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by HostRuntime.state.collectAsState()
            LaunchedEffect(Unit) {
                HostRuntime.initialize(this@MainActivity)
                HostRuntime.refreshDevices(this@MainActivity)
            }
            UsbBossScreen(
                state = state,
                onStart = { dispatchServiceAction(this, UsbBossService.ACTION_START) },
                onStop = { dispatchServiceAction(this, UsbBossService.ACTION_STOP) },
                onRefresh = { HostRuntime.refreshDevices(this) },
                onGrant = { HostRuntime.requestPermissions(this) },
                onToggleStartOnBoot = { HostRuntime.setStartOnBoot(this, !state.startOnBoot) },
                onToggleVerbose = { HostRuntime.setVerboseLogging(this, !state.verboseLogging) },
                onClearEvents = { HostRuntime.clearRecentEvents() },
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
    onToggleStartOnBoot: () -> Unit,
    onToggleVerbose: () -> Unit,
    onClearEvents: () -> Unit,
) {
    var showConnectionInfo by remember { mutableStateOf(false) }
    val background = Brush.verticalGradient(
        listOf(
            UsbBossPalette.canvas,
            Color(0xFF140C20),
            Color(0xFF1D102B),
            Color(0xFF120A1C),
        ),
    )

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = UsbBossPalette.canvas,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background),
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        FixedHeader(
                            state = state,
                            onStart = onStart,
                            onStop = onStop,
                            onRefresh = onRefresh,
                            onGrant = onGrant,
                            onToggleStartOnBoot = onToggleStartOnBoot,
                            onToggleVerbose = onToggleVerbose,
                            onShowConnectionInfo = { showConnectionInfo = true },
                        )
                    },
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            end = 20.dp,
                            bottom = innerPadding.calculateBottomPadding() + 28.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (state.lastError != null) {
                            item {
                                ErrorCard(state.lastError)
                            }
                        }

                        item {
                            SectionHeader(
                                title = "Connected Controllers",
                                subtitle = if (state.devices.isEmpty()) {
                                    "USBoss is ready and waiting for supported USB controller interfaces."
                                } else {
                                    "${state.devices.size} controller interface(s) currently visible on the Android host."
                                },
                            )
                        }

                        if (state.devices.isEmpty()) {
                            item {
                                EmptyStateCard()
                            }
                        } else {
                            items(
                                items = state.devices,
                                key = { candidate -> candidate.systemPath },
                            ) { candidate ->
                                DeviceCard(candidate)
                            }
                        }

                        if (state.verboseLogging || state.recentEvents.isNotEmpty()) {
                            item {
                                RecentEventsCard(
                                    state = state,
                                    onClearEvents = onClearEvents,
                                )
                            }
                        }
                    }
                }

                if (showConnectionInfo) {
                    ConnectionInfoDialog(
                        state = state,
                        onDismiss = { showConnectionInfo = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun FixedHeader(
    state: HostUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
    onToggleStartOnBoot: () -> Unit,
    onToggleVerbose: () -> Unit,
    onShowConnectionInfo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeroCard(state = state)
        ControlDeck(
            state = state,
            onStart = onStart,
            onStop = onStop,
            onRefresh = onRefresh,
            onGrant = onGrant,
            onToggleStartOnBoot = onToggleStartOnBoot,
            onToggleVerbose = onToggleVerbose,
            onShowConnectionInfo = onShowConnectionInfo,
        )
    }
}

@Composable
private fun HeroCard(state: HostUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            UsbBossPalette.surface,
                            UsbBossPalette.surfaceRaised,
                            Color(0xFF311A42),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "USBoss",
                            color = UsbBossPalette.textPrimary,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            text = "USB controller bridge for Android and Linux",
                            color = UsbBossPalette.textSecondary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    StatusBadge(state)
                }

                Text(
                    text = state.status,
                    color = UsbBossPalette.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatChip(
                        label = if (state.serviceRunning) "Host live" else "Host idle",
                        accent = if (state.serviceRunning) UsbBossPalette.success else UsbBossPalette.textMuted,
                    )
                    StatChip(
                        label = "${state.devices.size} controller${if (state.devices.size == 1) "" else "s"}",
                        accent = UsbBossPalette.pink,
                    )
                }

                Text(
                    text = "Linux session: ${state.connectedClient ?: "waiting"}",
                    color = UsbBossPalette.textSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ControlDeck(
    state: HostUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
    onToggleStartOnBoot: () -> Unit,
    onToggleVerbose: () -> Unit,
    onShowConnectionInfo: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = UsbBossPalette.canvasRaised.copy(alpha = 0.96f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = if (state.serviceRunning) onStop else onStart,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.serviceRunning) UsbBossPalette.pink else UsbBossPalette.violet,
                        contentColor = UsbBossPalette.canvas,
                    ),
                ) {
                    Text(if (state.serviceRunning) "Stop Host" else "Start Host")
                }
                Button(
                    onClick = onGrant,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UsbBossPalette.surfaceMuted,
                        contentColor = UsbBossPalette.textPrimary,
                    ),
                ) {
                    Text("Grant USB")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UsbBossPalette.surface,
                        contentColor = UsbBossPalette.textPrimary,
                    ),
                ) {
                    Text("Refresh")
                }
                Button(
                    onClick = onShowConnectionInfo,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UsbBossPalette.surface,
                        contentColor = UsbBossPalette.textPrimary,
                    ),
                ) {
                    Text("Connection Info")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToggleActionButton(
                    label = if (state.startOnBoot) "Boot: On" else "Boot: Off",
                    active = state.startOnBoot,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleStartOnBoot,
                )
                ToggleActionButton(
                    label = if (state.verboseLogging) "Verbose: On" else "Verbose: Off",
                    active = state.verboseLogging,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleVerbose,
                )
            }
        }
    }
}

@Composable
private fun ToggleActionButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) UsbBossPalette.orchid else UsbBossPalette.surface,
            contentColor = if (active) UsbBossPalette.canvas else UsbBossPalette.textSecondary,
        ),
    ) {
        Text(label)
    }
}

@Composable
private fun StatusBadge(state: HostUiState) {
    val accent = when {
        state.lastError != null -> UsbBossPalette.danger
        state.serviceRunning -> UsbBossPalette.success
        else -> UsbBossPalette.warning
    }
    val label = when {
        state.lastError != null -> "ATTN"
        state.serviceRunning -> "LIVE"
        else -> "IDLE"
    }

    Box(
        modifier = Modifier
            .background(
                color = accent.copy(alpha = 0.18f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatChip(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .background(
                color = accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = UsbBossPalette.textPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = UsbBossPalette.textPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            color = UsbBossPalette.textMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1625)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Attention Needed",
                color = UsbBossPalette.danger,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = message,
                color = Color(0xFFFFD9E1),
            )
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = UsbBossPalette.surface.copy(alpha = 0.94f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "No supported controller interfaces detected.",
                color = UsbBossPalette.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "It is fine to leave the host running before a controller is plugged in. Linux attach-all can stay connected and wait.",
                color = UsbBossPalette.textSecondary,
            )
            Text(
                text = "USBoss prefers XInput 360 interfaces for 8BitDo dongles and still supports HID devices when available.",
                color = UsbBossPalette.textMuted,
            )
        }
    }
}

@Composable
private fun DeviceCard(candidate: UsbCandidate) {
    val permissionAccent = if (candidate.hasPermission) UsbBossPalette.success else UsbBossPalette.warning
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = UsbBossPalette.surface.copy(alpha = 0.96f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = candidate.displayName,
                        color = UsbBossPalette.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${candidate.transportLabel}  iface ${candidate.interfaceNumber}",
                        color = UsbBossPalette.pink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                StatChip(
                    label = if (candidate.hasPermission) "Permission granted" else "Permission needed",
                    accent = permissionAccent,
                )
            }

            Text(
                text = "VID:PID ${candidate.vendorId.toString(16).padStart(4, '0')}:${candidate.productId.toString(16).padStart(4, '0')}",
                color = UsbBossPalette.textSecondary,
            )
            Text(
                text = "Input ${candidate.inputPacketSize} bytes  Output ${candidate.outputPacketSize} bytes",
                color = UsbBossPalette.textSecondary,
            )
            Text(
                text = candidate.systemPath,
                color = UsbBossPalette.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RecentEventsCard(
    state: HostUiState,
    onClearEvents: () -> Unit,
) {
    val recentEvents = state.recentEvents.takeLast(10).asReversed()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = UsbBossPalette.surfaceMuted.copy(alpha = 0.94f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Recent Activity",
                        color = UsbBossPalette.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (state.verboseLogging) {
                            "Verbose logging is enabled. These events mirror the most useful USBoss runtime breadcrumbs."
                        } else {
                            "Turn on verbose logging for deeper hardware diagnostics."
                        },
                        color = UsbBossPalette.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = onClearEvents,
                    enabled = state.recentEvents.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UsbBossPalette.surface,
                        contentColor = UsbBossPalette.textPrimary,
                    ),
                ) {
                    Text("Clear")
                }
            }

            if (recentEvents.isEmpty()) {
                Text(
                    text = "No recent events captured yet.",
                    color = UsbBossPalette.textSecondary,
                )
            } else {
                recentEvents.forEach { event ->
                    SelectionContainer {
                        Text(
                            text = event,
                            color = UsbBossPalette.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionInfoDialog(
    state: HostUiState,
    onDismiss: () -> Unit,
) {
    val recommendedCommand = recommendedLinuxCommand(state)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Connection Info",
                color = UsbBossPalette.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            SelectionContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Linux command",
                        color = UsbBossPalette.pink,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = recommendedCommand,
                        color = UsbBossPalette.textPrimary,
                    )
                    Text(
                        text = "Discovery UDP port: ${state.discoveryPort}",
                        color = UsbBossPalette.textSecondary,
                    )
                    Text(
                        text = "TCP stream port: ${state.tcpPort}",
                        color = UsbBossPalette.textSecondary,
                    )
                    Text(
                        text = "Linux session state: ${state.connectedClient ?: "none"}",
                        color = UsbBossPalette.textSecondary,
                    )
                    Text(
                        text = "ADB logging",
                        color = UsbBossPalette.pink,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "adb logcat -s USBoss",
                        color = UsbBossPalette.textPrimary,
                    )
                    Text(
                        text = "Tip: attach-all is the recommended everyday mode and will reconnect automatically when the host or controller returns.",
                        color = UsbBossPalette.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = UsbBossPalette.pink)
            }
        },
        containerColor = UsbBossPalette.surfaceRaised,
        shape = RoundedCornerShape(28.dp),
    )
}

private fun recommendedLinuxCommand(state: HostUiState): String {
    val attachCommand = if (state.devices.size == 1) "usboss-client attach" else "usboss-client attach-all"
    return if (state.serverIp.isBlank()) {
        attachCommand
    } else {
        "$attachCommand --host ${state.serverIp}:${state.tcpPort}"
    }
}
