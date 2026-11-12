package com.example.workoutpoc.exchange

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.*
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
    // A review opens at its top (title and save), whatever position the archive list was scrolled to.
    AppScreen("Intercambio JSON", "Volver a programas", onBack, busy = ui.busy, message = ui.message, listState = rememberViewListState(ui.preview?.importId)) {
        item { SupportingText("Contexto, propuestas y resultados se guardan en un archivo independiente. Importar no aprueba propuestas ni modifica programas o sesiones.") }
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar intercambios", onRetry, enabled = !ui.busy) }
        val data = ui.preview
        if (data == null) {
            item {
                ContentCard {
                    Text("Importar", style = MaterialTheme.typography.titleMedium)
                    PrimaryAction("Importar archivo de intercambio", onFile, enabled = ui.ready && !ui.busy, icon = AppIcons.Upload)
                    OutlinedTextField(text, { if (it.length <= ExchangeCodec.MAX_BYTES) text = it },
                        label = { Text("JSON de intercambio") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
                    SecondaryAction("Revisar intercambio", { onText(text) }, enabled = ui.ready && !ui.busy && text.isNotBlank())
                }
            }
            item {
                ContentCard {
                    Text("Preparar para el agente", style = MaterialTheme.typography.titleMedium)
                    TonalAction("Preparar contexto local", { onGenerate(ExchangeType.TrainingContext) }, enabled = ui.ready && !ui.busy)
                    TonalAction("Preparar resultados locales", { onGenerate(ExchangeType.TrainingResults) }, enabled = ui.ready && !ui.busy)
                    SupportingText("Los resultados locales incluyen la sesión demo disponible; el contexto para el agente no la incluye. La fase, los objetivos y las reglas del contexto salen solo de tus respuestas en Preguntas de planificación.",
                        small = true)
                }
            }
            item { SectionHeader("Envíos firmados (MCP)",
                supporting = "Empareja un servidor remoto con una clave. Sus propuestas firmadas se verifican (firma, clave vigente, tipo permitido, antigüedad y repetición) antes de previsualizarlas, y siguen pendientes de tu aprobación. Cualquier otro envío firmado se rechaza.") }
            item { SecondaryAction("Generar clave de emparejamiento", onPair, enabled = ui.ready && !ui.busy, icon = AppIcons.Key) }
            items(sync.keys, key = { "key-${it.keyId}" }) { k ->
                ContentCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(AppIcons.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text("${k.keyId} · ${if (k.revoked) "revocada" else "activa"}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusPill(if (k.revoked) "revocada" else "activa", if (k.revoked) Tone.Error else Tone.Success)
                    }
                    if (!k.revoked) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        QuietAction("Copiar clave ${k.keyId}", { onCopyKey(com.example.workoutpoc.sync.SignedSync.shareable(k)) }, enabled = !ui.busy, icon = AppIcons.Copy)
                        QuietAction("Revocar ${k.keyId}", { onRevoke(k.keyId) }, enabled = !ui.busy, destructive = true)
                    }
                }
            }
            item { SectionHeader("Intercambios guardados") }
            if (archive.packages.isEmpty()) item { EmptyState("Aún no hay intercambios guardados.", icon = AppIcons.Swap) }
            items(archive.packages, key = { it.importId }) { entry ->
                NavRow("${entry.type} · ${entry.importId}", { onSelect(entry) }, enabled = !ui.busy, icon = AppIcons.Swap)
            }
        } else {
            item { SectionHeader("Revisar intercambio", supporting = "${data.type} · ${data.importId}") }
            ui.signedBy?.let { k -> item { Banner("Firma verificada con la clave $k. Al guardar se vuelve a verificar y la propuesta queda pendiente de aprobación.",
                tone = Tone.Success) } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PrimaryAction("Guardar intercambio", onSave, enabled = ui.ready && !ui.busy, icon = AppIcons.Check)
                    SecondaryAction("Cerrar revisión", onCancel, enabled = !ui.busy)
                }
            }
            if (data.context?.measurements?.isNotEmpty() == true) item {
                ContentCard {
                    // The checkbox stays its own control (a native CheckBox node, which ExchangeDeviceTest looks up).
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeMeasurements, { includeMeasurements = it })
                        Text("Incluir mediciones corporales al copiar o exportar", modifier = Modifier.weight(1f))
                    }
                    SupportingText("Excluirlas crea un nuevo importId para conservar intacto el contexto original.", small = true)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TonalAction("Exportar archivo JSON", { onExport(includeMeasurements) }, Modifier.weight(1f), enabled = !ui.busy, icon = AppIcons.Download)
                    TonalAction("Copiar JSON", { onCopy(includeMeasurements) }, Modifier.weight(1f), enabled = !ui.busy, icon = AppIcons.Copy)
                }
            }
            item { SupportingText("Contenido completo para revisar (las mediciones corporales solo se comparten al seleccionarlas).", small = true) }
            items(previewChunks.size) { index ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Text(previewChunks[index], Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }

}
