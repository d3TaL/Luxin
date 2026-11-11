package io.github.d3tal.luxin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.d3tal.luxin.capture.CaptureResult
import io.github.d3tal.luxin.capture.CaptureService
import io.github.d3tal.luxin.overlay.OverlayService
import io.github.d3tal.luxin.ui.theme.LuxinTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LuxinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CaptureSpikeScreen()
                }
            }
        }
    }
}

@Composable
fun CaptureSpikeScreen() {
    val context = LocalContext.current

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val isOverlayActive by OverlayService.isShowing.collectAsStateWithLifecycle()
    val isCaptureActive by CaptureService.isRunning.collectAsStateWithLifecycle()
    val captureResult by CaptureService.latestResult.collectAsStateWithLifecycle()

    // Activity result launcher for MediaProjection permission
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(context, result.resultCode, result.data!!)
            Toast.makeText(context, "Служба захвата запущена", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Разрешение на захват отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    // Activity result launcher for Notification permission
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    LaunchedEffect(Unit) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App header
        Text(
            text = "Luxin Capture Spike",
            style = MaterialTheme.fontSizeTitleLarge(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Проверка изоляции захвата экрана и системного оверлея на Android 14 (SM-S938B)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        // Card 1: Overlay Permission & Control
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "1. Системный оверлей",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                StatusRow(
                    label = "Разрешение «Поверх других окон»",
                    active = hasOverlayPermission,
                    activeText = "Предоставлено",
                    inactiveText = "Требуется"
                )

                if (!hasOverlayPermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Открыть настройки разрешений")
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow(
                        label = "Тестовая метка над миникартой",
                        active = isOverlayActive,
                        activeText = "Отображается (пурпурный круг)",
                        inactiveText = "Скрыта"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { OverlayService.start(context) },
                            enabled = !isOverlayActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Показать")
                        }
                        OutlinedButton(
                            onClick = { OverlayService.stop(context) },
                            enabled = isOverlayActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Скрыть")
                        }
                    }
                }
            }
        }

        // Card 2: Screen Capture (MediaProjection)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "2. Захват экрана (MediaProjection)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "В появившемся системном диалоге выберите «Одно приложение» и укажите Genshin Impact для проверки изоляции.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                StatusRow(
                    label = "Служба захвата",
                    active = isCaptureActive,
                    activeText = "Активна (фоновый сервис)",
                    inactiveText = "Остановлена"
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                        },
                        enabled = !isCaptureActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Запустить")
                    }
                    OutlinedButton(
                        onClick = { CaptureService.stop(context) },
                        enabled = isCaptureActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Остановить")
                    }
                }
            }
        }

        // Card 3: Snapshot and Leak Check
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "3. Проверка утечки оверлея в кадр",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { CaptureService.takeSnapshot(context, hideOverlayDuringCapture = false) },
                    enabled = isCaptureActive,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("📸 Снимок (обычный)")
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedButton(
                    onClick = { CaptureService.takeSnapshot(context, hideOverlayDuringCapture = true) },
                    enabled = isCaptureActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("✨ Снимок (скрыть оверлей на 1 кадр)")
                }

                if (captureResult != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    CaptureResultView(result = captureResult!!)
                }
            }
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    active: Boolean,
    activeText: String,
    inactiveText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (active) Color(0xFF4CAF50) else Color(0xFFF44336),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (active) activeText else inactiveText,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) Color(0xFF4CAF50) else Color(0xFFF44336),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun CaptureResultView(result: CaptureResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Результат анализа кадра (${result.timestamp}):",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        if (result.error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF44336)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = result.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFF44336)
                )
            }
        } else {
            Text(
                text = "Разрешение: ${result.width} × ${result.height} | Захват: ${result.captureMs} мс",
                style = MaterialTheme.typography.bodyMedium
            )

            // Result banner
            Surface(
                color = if (result.overlayLeaked) Color(0x33F44336) else Color(0x334CAF50),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (result.overlayLeaked) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (result.overlayLeaked) Color(0xFFF44336) else Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (result.overlayLeaked)
                                "ОВЕРЛЕЙ ПОПАЛ В КАДР"
                            else
                                "ОВЕРЛЕЙ НЕ ОБНАРУЖЕН В КАДРЕ",
                            fontWeight = FontWeight.Bold,
                            color = if (result.overlayLeaked) Color(0xFFF44336) else Color(0xFF4CAF50),
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (result.overlayLeaked)
                                "Пиксели метки присутствуют (${result.magentaPixelCount} px). Требуется маскирование при рисовании."
                            else
                                "Изоляция Android 14 работает! Захват игры чист от системного оверлея.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (result.filePath.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Файл кадра сохранен:\n${result.filePath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun MaterialTheme.fontSizeTitleLarge() = typography.titleLarge
