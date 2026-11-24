package io.github.d3tal.luxin.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.d3tal.luxin.capture.CaptureService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun DiagnosticLogExportCard() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val destination by DiagnosticLogExporter.destination.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val chooseDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    DiagnosticLogExporter.saveDestination(context, uri)
                    message = "Папка сохранена"
                    error = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = "Не удалось сохранить доступ. Выберите другую папку или подпапку Download."
                    error = true
                } finally {
                    busy = false
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            DiagnosticLogExporter.loadDestination(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message = "Сохранённая папка недоступна. Выберите её заново."
            error = true
        } finally {
            busy = false
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Логи трекинга", style = MaterialTheme.typography.titleMedium)
            Text("После теста карты вернитесь сюда и выгрузите журнал. Сохраняются последние доступные " +
                "записи Android только о трекинге Luxin, без скриншотов и общего системного лога. " +
                "Старые записи могут быть вытеснены из буфера; после перезапуска приложения прежний процесс не выгружается.",
                style = MaterialTheme.typography.bodySmall)
            Text("Папка: ${destination?.label ?: "не выбрана"}")
            Text("Рекомендуется создать папку Download/LuxinLogs и выбрать её. На Android нельзя выбрать сам корень Download.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { chooseDirectory.launch(destination?.uri) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) {
                Text("Выбрать папку для логов")
            }
            Button(onClick = {
                val target = destination ?: return@Button
                busy = true
                message = null
                error = false
                scope.launch {
                    try {
                        val result = DiagnosticLogExporter.export(context, target)
                        message = "Сохранено: ${result.filename}\nЗаписей: ${result.records}" +
                            if (result.limited) "\nЖурнал ограничен по размеру; выгрузите его раньше после следующего теста." else ""
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        error = true
                        message = "Не удалось выгрузить журнал. Проверьте доступ к папке и повторите сразу после " +
                            "работы трекера. Если записей нет, прошивка могла ограничить доступ к logcat."
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy && destination != null, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Подождите…" else "Выгрузить логи")
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
internal fun BigMapFrameDumpCard(isCaptureActive: Boolean) {
    val context = LocalContext.current.applicationContext
    val destination by DiagnosticLogExporter.destination.collectAsStateWithLifecycle()
    val dumpState by BigMapFrameDumpStatus.state.collectAsStateWithLifecycle()
    val canToggle = !dumpState.isBusyEncoding &&
        (dumpState.isToggleChecked || (isCaptureActive && destination != null))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Дамп кадров большой карты",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = dumpState.isToggleChecked,
                    onCheckedChange = { enabled ->
                        if (enabled) CaptureService.startFrameDump(context)
                        else CaptureService.stopFrameDump(context)
                    },
                    enabled = canToggle
                )
            }
            Text(
                text = "Через ${dumpState.startDelaySec} с после включения дамп записывает " +
                    "кадры в буфер до ${dumpState.memoryBudgetMb} МБ. Размер кадров — до 720p: " +
                    "зависит от памяти, доступной приложению. После записи сохраняются PNG " +
                    "(серые кадры и каждый 10-й в цвете), " +
                    "frames.csv, summary.txt и лог трекинга в подпапку bigmap_dump_…. " +
                    "Автостоп: заполнение буфера или ${dumpState.maxDurationSec} с.",
                style = MaterialTheme.typography.bodySmall
            )
            if (destination == null) {
                Text(
                    text = "Сначала выберите папку в карточке «Логи трекинга» выше.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!isCaptureActive && !dumpState.isToggleChecked && !dumpState.isBusyEncoding) {
                Text(
                    text = "Сначала запустите захват экрана (раздел 3 ниже).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (dumpState.phase == DumpPhase.COUNTDOWN) {
                Text(
                    text = "Старт записи через ${dumpState.countdownRemainingSec} с — переключитесь в игру",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (dumpState.phase == DumpPhase.CAPTURING && dumpState.poolCapacityFrames > 0) {
                val progress = (dumpState.capturedFrames.toFloat() / dumpState.poolCapacityFrames.toFloat())
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Захват в RAM (${dumpState.dumpWidth}×${dumpState.dumpHeight}): " +
                        "${dumpState.capturedFrames}/${dumpState.poolCapacityFrames} | " +
                        "Пропущено: ${dumpState.droppedFrames}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (dumpState.phase == DumpPhase.ENCODING && dumpState.capturedFrames > 0) {
                val progress = (dumpState.encodedFrames.toFloat() / dumpState.capturedFrames.toFloat())
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Кодирование PNG: ${dumpState.encodedFrames}/${dumpState.capturedFrames}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            dumpState.folderName?.let { folder ->
                Text("Подпапка: $folder", style = MaterialTheme.typography.bodySmall)
            }
            if (dumpState.achievedFps != null && dumpState.capturedFrames > 0 && dumpState.phase != DumpPhase.CAPTURING) {
                val maxGap = dumpState.maxGapMs ?: 0.0
                Text(
                    text = String.format(
                        Locale.US,
                        "Итого: %d кадр. | %.1f fps | макс. разрыв %.1f мс | пропущено: %d",
                        dumpState.capturedFrames,
                        dumpState.achievedFps,
                        maxGap,
                        dumpState.droppedFrames
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            dumpState.message?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall)
            }
            dumpState.logExportNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
            dumpState.error?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
