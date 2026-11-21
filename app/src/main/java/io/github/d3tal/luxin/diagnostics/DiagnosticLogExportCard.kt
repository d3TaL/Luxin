package io.github.d3tal.luxin.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DiagnosticLogExportCard() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf<DiagnosticLogDestination?>(null) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val chooseDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    destination = DiagnosticLogExporter.saveDestination(context, uri)
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
            destination = DiagnosticLogExporter.loadDestination(context)
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
