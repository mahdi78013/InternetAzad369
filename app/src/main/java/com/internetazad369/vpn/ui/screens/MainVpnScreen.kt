package com.internetazad369.vpn.ui.screens

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.internetazad369.vpn.data.model.ConnectionState
import com.internetazad369.vpn.data.model.TrafficStats
import com.internetazad369.vpn.ui.theme.AccentCyan
import com.internetazad369.vpn.ui.theme.AccentCyanGlow
import com.internetazad369.vpn.ui.theme.BackgroundDark
import com.internetazad369.vpn.ui.theme.DisconnectedBlue
import com.internetazad369.vpn.ui.theme.DisconnectedBlueGlow
import com.internetazad369.vpn.ui.theme.ErrorRed
import com.internetazad369.vpn.ui.theme.ErrorRedGlow
import com.internetazad369.vpn.ui.theme.PrimaryGreen
import com.internetazad369.vpn.ui.theme.PrimaryGreenGlow
import com.internetazad369.vpn.ui.theme.SurfaceDark
import com.internetazad369.vpn.ui.theme.SurfaceDarkVariant
import com.internetazad369.vpn.ui.theme.TextMuted
import com.internetazad369.vpn.ui.theme.TextPrimary
import com.internetazad369.vpn.ui.theme.TextSecondary
import com.internetazad369.vpn.ui.viewmodel.VpnViewModel
import java.util.Locale

@Composable
fun MainVpnScreen(
    viewModel: VpnViewModel,
    vpnPermissionLauncher: ActivityResultLauncher<Intent>
) {
    val state by viewModel.connectionState.collectAsState()
    val traffic by viewModel.trafficStats.collectAsState()
    val durationSeconds by viewModel.connectionDurationSeconds.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = BackgroundDark
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. App Header
                AppHeader()

                // 2. Profile Badge
                ProfileBadge()

                // 3. Central Big Button and Status
                ConnectionCenterpiece(
                    state = state,
                    onButtonClick = { viewModel.onButtonClick(vpnPermissionLauncher) }
                )

                // 4. Live Stats Cards (Visible only when connected)
                AnimatedVisibility(
                    visible = state is ConnectionState.Connected,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ConnectedStatsSection(
                        durationSeconds = durationSeconds,
                        traffic = traffic
                    )
                }

                // 5. Error banner if in error state
                AnimatedVisibility(
                    visible = state is ConnectionState.Error,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val err = (state as? ConnectionState.Error)?.errorMessage ?: "اتصال برقرار نشد"
                    ErrorBanner(
                        message = err,
                        onRetry = { viewModel.onButtonClick(vpnPermissionLauncher) }
                    )
                }

                // 6. Security Footer
                SecurityFooter()
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .border(1.dp, PrimaryGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Logo",
                    tint = PrimaryGreen,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "اینترنت آزاد ۳۶۹",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "تونل امن اختصاصی با یک دکمه",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ProfileBadge() {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceDarkVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = PrimaryGreen,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "پروفایل اختصاصی: Mahsa",
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDarkVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "هسته sing-box",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                    color = AccentCyan
                )
            }
        }
    }
}

@Composable
private fun ConnectionCenterpiece(
    state: ConnectionState,
    onButtonClick: () -> Unit
) {
    val isConnecting = state is ConnectionState.Connecting ||
            state is ConnectionState.Fetching ||
            state is ConnectionState.Validating ||
            state is ConnectionState.WaitingForPermission ||
            state is ConnectionState.Disconnecting

    val isConnected = state is ConnectionState.Connected
    val isError = state is ConnectionState.Error

    val mainColor by animateColorAsState(
        targetValue = when {
            isConnected -> PrimaryGreen
            isConnecting -> AccentCyan
            isError -> ErrorRed
            else -> DisconnectedBlue
        },
        animationSpec = tween(400),
        label = "ButtonColor"
    )

    val glowColor by animateColorAsState(
        targetValue = when {
            isConnected -> PrimaryGreenGlow
            isConnecting -> AccentCyanGlow
            isError -> ErrorRedGlow
            else -> DisconnectedBlueGlow
        },
        animationSpec = tween(400),
        label = "GlowColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnecting || isConnected) 1.06f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 12.dp)
    ) {
        // Outer pulsing ring
        Box(
            modifier = Modifier
                .size(230.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(glowColor),
            contentAlignment = Alignment.Center
        ) {
            // Mid ring
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .border(2.dp, mainColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Interactive inner button
                Box(
                    modifier = Modifier
                        .size(165.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    mainColor.copy(alpha = 0.25f),
                                    SurfaceDark
                                )
                            )
                        )
                        .border(3.dp, mainColor, CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            onButtonClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                color = mainColor,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(38.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isError) Icons.Default.Refresh else Icons.Default.PowerSettingsNew,
                                contentDescription = state.buttonLabel,
                                tint = mainColor,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.buttonLabel,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // State indicator text
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(mainColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = state.persianStatus,
                style = MaterialTheme.typography.titleLarge,
                color = mainColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ConnectedStatsSection(
    durationSeconds: Long,
    traffic: TrafficStats
) {
    val formattedDuration = remember(durationSeconds) {
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    val downSpeedFormatted = remember(traffic.downlinkSpeedBps) {
        formatSpeed(traffic.downlinkSpeedBps)
    }

    val upSpeedFormatted = remember(traffic.uplinkSpeedBps) {
        formatSpeed(traffic.uplinkSpeedBps)
    }

    val totalDownloadedFormatted = remember(traffic.downlinkTotalBytes) {
        formatBytes(traffic.downlinkTotalBytes)
    }

    val totalUploadedFormatted = remember(traffic.uplinkTotalBytes) {
        formatBytes(traffic.uplinkTotalBytes)
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceDarkVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Duration row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "مدت اتصال",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Speed cards row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Download
                StatItem(
                    modifier = Modifier.weight(1f),
                    label = "دانلود",
                    speed = downSpeedFormatted,
                    total = totalDownloadedFormatted,
                    icon = Icons.Default.ArrowDownward,
                    color = PrimaryGreen
                )

                // Upload
                StatItem(
                    modifier = Modifier.weight(1f),
                    label = "آپلود",
                    speed = upSpeedFormatted,
                    total = totalUploadedFormatted,
                    icon = Icons.Default.ArrowUpward,
                    color = AccentCyan
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    modifier: Modifier = Modifier,
    label: String,
    speed: String,
    total: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDarkVariant.copy(alpha = 0.5f))
            .padding(10.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = speed,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                color = color
            )
            Text(
                text = "مجموع: $total",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                color = TextMuted
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit
) {
    Surface(
        color = ErrorRedGlow,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ErrorRed)
                    .clickable { onRetry() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "تلاش مجدد",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SecurityFooter() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        Text(
            text = "رمزنگاری سراسری TLS / Reality • ضد نشت ترافیک",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    val kb = bytesPerSecond / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB/s", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB/s", kb)
        else -> String.format(Locale.US, "%d B/s", bytesPerSecond)
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
