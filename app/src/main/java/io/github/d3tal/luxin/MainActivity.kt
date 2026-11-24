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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.d3tal.luxin.capture.CaptureResult
import io.github.d3tal.luxin.capture.CaptureService
import io.github.d3tal.luxin.data.KongyingSyncManager
import io.github.d3tal.luxin.diagnostics.BigMapFrameDumpCard
import io.github.d3tal.luxin.diagnostics.DiagnosticLogExportCard
import io.github.d3tal.luxin.overlay.OverlayService
import io.github.d3tal.luxin.tracker.NativeSplitTracker
import io.github.d3tal.luxin.tracker.ReferencePackStore
import io.github.d3tal.luxin.tracker.LiveTrackingStatus
import io.github.d3tal.luxin.tracker.formatTrackingHud
import io.github.d3tal.luxin.ui.theme.LuxinTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()

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

    var nativeJniStatus by remember { mutableStateOf("Не проверено") }
    var isSyncing by remember { mutableStateOf(false) }
    var isImportingPack by remember { mutableStateOf(false) }
    var packSummary by remember { mutableStateOf("Эталоны не импортированы") }
    var packError by remember { mutableStateOf<String?>(null) }
    val liveTracking by LiveTrackingStatus.state.collectAsStateWithLifecycle()

    val referencePackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && LiveTrackingStatus.state.value.enabled) {
            packError = "Сначала остановите трекер перед сменой набора"
        } else if (uri != null) {
            isImportingPack = true
            packError = null
            scope.launch {
                try {
                    val pack = ReferencePackStore.importPack(context.applicationContext, uri)
                    packSummary = "${pack.manifest.title} — эталонов: ${pack.manifest.layers.size}"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    packError = e.message ?: "Не удалось импортировать набор"
                } finally {
                    isImportingPack = false
                }
            }
        }
    }

    val isOverlayActive by OverlayService.isShowing.collectAsStateWithLifecycle()
    val isCaptureActive by CaptureService.isRunning.collectAsStateWithLifecycle()
    val captureResult by CaptureService.latestResult.collectAsStateWithLifecycle()
    val syncSummary by KongyingSyncManager.syncState.collectAsStateWithLifecycle()

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

    // Refresh overlay and notification permissions immediately whenever returning to the app from Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        val installed = try {
            withContext(Dispatchers.IO) { ReferencePackStore.loadInstalled(context.applicationContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            packError = "Проверка сохранённых эталонов: ${e.message}"
            null
        }
        if (installed != null) packSummary = "${installed.manifest.title} — эталонов: ${installed.manifest.layers.size}"
        hasOverlayPermission = Settings.canDrawOverlays(context)
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            NativeSplitTracker().use { tracker ->
                nativeJniStatus = tracker.runSelfTest()
            }
        } catch (e: Throwable) {
            nativeJniStatus = "JNI Error: ${e.message}"
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
            text = "Luxin v0.2 — Tracker & Data",
            style = MaterialTheme.fontSizeTitleLarge(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "C++17 SplitTracker (OpenCV 4.10 JNI), синхронизация Kongying Tavern и системный оверлей (SM-S938B)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        // Card 1: C++ Native Tracker & Kongying Sync
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "1. Ядро C++ (JNI) и данные Kongying",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Статус C++ JNI: $nativeJniStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nativeJniStatus.startsWith("OK")) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "База Kongying: ${syncSummary.status}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (syncSummary.areaCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Областей: ${syncSummary.areaCount} | Предметов: ${syncSummary.itemCount} | " +
                                "Чанков меток: ${syncSummary.markerChunksTotal} | " +
                                "Загружено меток (проба): ${syncSummary.markerCountSample} (подземных: ${syncSummary.undergroundMarkerCount})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }

                if (syncSummary.error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = syncSummary.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        isSyncing = true
                        scope.launch {
                            KongyingSyncManager.syncMetadataAndSampleChunk(context)
                            isSyncing = false
                        }
                    },
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSyncing) "Загрузка из Kongying..." else "Синхронизировать базу Kongying")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Живой трекер — диагностика", style = MaterialTheme.typography.titleMedium)
                Text(packSummary, style = MaterialTheme.typography.bodyMedium)
                Text("Импортируйте ZIP с эталонами нужной области. Набор ограничен этой областью, не всем Тейватом. " +
                    "Для первого теста выберите захват одного приложения Genshin. Метки предметов пока не рисуются.",
                    style = MaterialTheme.typography.bodySmall)
                packError?.let { Text(it, color = Color(0xFFF44336)) }
                OutlinedButton(onClick = {
                    referencePackLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }, enabled = !isImportingPack && !liveTracking.enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isImportingPack) "Импорт эталонов…" else "Импортировать ZIP эталонов")
                }
                Button(onClick = {
                    if (liveTracking.enabled) CaptureService.stopTracking(context)
                    else CaptureService.startTracking(context)
                }, enabled = isCaptureActive && !isImportingPack, modifier = Modifier.fillMaxWidth()) {
                    Text(if (liveTracking.enabled) "Остановить трекер" else "Запустить трекер (2 кадра/с)")
                }
                Text(formatTrackingHud(liveTracking), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        DiagnosticLogExportCard()
        BigMapFrameDumpCard(isCaptureActive = isCaptureActive)

        // Card 2: Overlay Permission & Control
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
                        text = "2. Системный оверлей",
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
                        activeText = "Отображается",
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

        // Card 3: Screen Capture (MediaProjection)
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
                        text = "3. Захват экрана (MediaProjection)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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

        // Card 4: Snapshot and Leak Check
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
                        text = "4. Проверка захвата кадра",
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
                text = "Кадр: ${result.width} × ${result.height} | Снимок и сохранение: ${result.captureMs} мс",
                style = MaterialTheme.typography.bodyMedium
            )

            // Result banner
            Surface(
                color = if (result.overlayLeaked) Color(0x33F44336) else Color(0x33FFB74D),
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
                        tint = if (result.overlayLeaked) Color(0xFFF44336) else Color(0xFFFFB74D)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when {
                                !result.markerChecked -> "ПРОВЕРКА МЕТКИ НЕДОСТУПНА"
                                result.overlayLeaked -> "ТЕСТОВАЯ МЕТКА ОБНАРУЖЕНА"
                                else -> "ТЕСТОВАЯ МЕТКА НЕ НАЙДЕНА"
                            },
                            fontWeight = FontWeight.Bold,
                            color = if (result.overlayLeaked) Color(0xFFF44336) else Color(0xFFFFB74D),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${result.magentaPixelCount} пурпурных px. ${result.markerCheckNote}",
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
