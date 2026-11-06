package com.example.workoutpoc.exchange

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExchangeScreen(archive: ExchangeArchive, ui: ExchangeUiState, onBack: () -> Unit,
    onFile: () -> Unit, onText: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
    onRetry: () -> Unit, onSelect: (ExchangePackage) -> Unit, onGenerate: (ExchangeType) -> Unit,
    onExport: (Boolean) -> Unit, onCopy: (Boolean) -> Unit,
    sync: com.example.workoutpoc.sync.SyncState = com.example.workoutpoc.sync.SyncState(),
    onPair: () -> Unit = {}, onRevoke: (String) -> Unit = {}, onCopyKey: (String) -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    var includeMeasurements by rememberSaveable(ui.preview?.importId) { mutableStateOf(false) }
    val previewChunks = remember(ui.preview) { ui.preview?.let { ExchangeCodec.json.encodeToString(it).chunked(4000) }.orEmpty() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = onBack) { Text("Volver a programas") } }
                item { Text("Intercambio JSON", style = MaterialTheme.typography.headlineLarge) }
                item { Text("Contexto, propuestas y resultados se guardan en un archivo independiente. Importar no aprueba propuestas ni modifica programas o sesiones.") }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar intercambios") } }
                val data = ui.preview
                if (data == null) {
                    item { Button(onClick = onFile, enabled = ui.ready && !ui.busy) { Text("Importar archivo de intercambio") } }
                    item { OutlinedTextField(text, { if (it.length <= ExchangeCodec.MAX_BYTES) text = it },
                        label = { Text("JSON de intercambio") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
                    item { OutlinedButton(onClick = { onText(text) }, enabled = ui.ready && !ui.busy && text.isNotBlank()) { Text("Revisar intercambio") } }
                    item { Button(onClick = { onGenerate(ExchangeType.TrainingContext) }, enabled = ui.ready && !ui.busy) { Text("Preparar contexto local") } }
                    item { Button(onClick = { onGenerate(ExchangeType.TrainingResults) }, enabled = ui.ready && !ui.busy) { Text("Preparar resultados locales") } }
                    item { Text("Los resultados locales incluyen la sesión demo disponible; no hay historial adicional registrado. La fase, los objetivos y las reglas del contexto salen solo de tus respuestas en Preguntas de planificación.") }
                    item { Text("Envíos firmados (MCP)", style = MaterialTheme.typography.titleLarge) }
                    item { Text("Empareja un servidor remoto con una clave. Sus propuestas firmadas se verifican (firma, clave vigente, tipo permitido, antigüedad y repetición) antes de previsualizarlas, y siguen pendientes de tu aprobación. Cualquier otro envío firmado se rechaza.",
                        style = MaterialTheme.typography.bodySmall) }
                    item { OutlinedButton(onClick = onPair, enabled = ui.ready && !ui.busy) { Text("Generar clave de emparejamiento") } }
                    items(sync.keys, key = { "key-${it.keyId}" }) { k ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${k.keyId} · ${if (k.revoked) "revocada" else "activa"}", style = MaterialTheme.typography.titleSmall)
                                if (!k.revoked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onCopyKey(com.example.workoutpoc.sync.SignedSync.shareable(k)) }, enabled = !ui.busy) { Text("Copiar clave ${k.keyId}") }
                                    TextButton(onClick = { onRevoke(k.keyId) }, enabled = !ui.busy) { Text("Revocar ${k.keyId}") }
                                }
                            }
                        }
                    }
                    item { Text("Intercambios guardados", style = MaterialTheme.typography.titleLarge) }
                    items(archive.packages, key = { it.importId }) { entry ->
                        OutlinedButton(onClick = { onSelect(entry) }, enabled = !ui.busy) { Text("${entry.type} · ${entry.importId}") }
                    }
                } else {
                    item { Text("Revisar intercambio", style = MaterialTheme.typography.titleLarge) }
                    item { Text("${data.type} · ${data.importId}") }
                    ui.signedBy?.let { k -> item { Text("Firma verificada con la clave $k. Al guardar se vuelve a verificar y la propuesta queda pendiente de aprobación.",
                        color = MaterialTheme.colorScheme.primary) } }
                    item { Button(onClick = onSave, enabled = ui.ready && !ui.busy) { Text("Guardar intercambio") } }
                    item { OutlinedButton(onClick = onCancel, enabled = !ui.busy) { Text("Cerrar revisión") } }
                    if (data.context?.measurements?.isNotEmpty() == true) item {
                        Row { Checkbox(includeMeasurements, { includeMeasurements = it }); Text("Incluir mediciones corporales al copiar o exportar") }
                        Text("Excluirlas crea un nuevo importId para conservar intacto el contexto original.")
                    }
                    item { Button(onClick = { onExport(includeMeasurements) }, enabled = !ui.busy) { Text("Exportar archivo JSON") } }
                    item { OutlinedButton(onClick = { onCopy(includeMeasurements) }, enabled = !ui.busy) { Text("Copiar JSON") } }
                    item { Text("Contenido completo para revisar (las mediciones corporales solo se comparten al seleccionarlas).") }
                    items(previewChunks.size) { index -> Text(previewChunks[index]) }
                }
            }
        }
    }
}
