package com.example.workoutpoc.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.workoutpoc.ui.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackupScreen(ui: BackupUiState, onBack: () -> Unit, onExport: () -> Unit, onChooseRestore: () -> Unit,
                 onCancel: () -> Unit, onConfirm: () -> Unit) {
    var confirming by remember(ui.review) { mutableStateOf(false) }
    BackHandler(ui.busy || ui.review != null) { if (!ui.busy) onCancel() }
    AppScreen("Respaldo completo", "Volver a Ajustes", { if (ui.review != null) onCancel() else onBack() },
        backEnabled = !ui.busy, busy = ui.busy, message = ui.message) {
        val review = ui.review
        if (review == null) {
            item { SupportingText("Guarda tus datos locales en un archivo para trasladarlos a otra instalación de Auto-Gym.") }
            item { PrimaryAction("Exportar respaldo completo", onExport, enabled = !ui.busy, icon = AppIcons.Download) }
            item { SecondaryAction("Restaurar respaldo", onChooseRestore, enabled = !ui.busy, icon = AppIcons.Upload) }
            item { SupportingText("El archivo incluye registros y mediciones personales. Guárdalo en un lugar seguro. Las claves de emparejamiento y la llave API de Claude no se incluyen.") }
        } else {
            item { SectionHeader("Revisar respaldo") }
            item { ContentCard {
                val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(review.backup.createdAt)
                LabeledValue("Fecha", date)
                LabeledValue("Aplicación", "${review.backup.app.versionName} (${review.backup.app.versionCode})")
                SupportingText("Cada fila muestra: respaldo / este dispositivo.")
                LabeledValue("Modo de registro", "${review.backup.data.settings.loggingMode.label} / ${review.current.settings.loggingMode.label}")
                LabeledValue("Expectativas de propuestas", "${review.backup.data.proposals.expectations.size} / ${review.current.proposals.expectations.size}")
                SupportingText("También se reemplaza la configuración de reglas de propuestas.", small = true)
            } }
            val currentRows = review.current.counts().rows()
            items(review.backup.data.counts().rows().withIndex().toList(), key = { it.index }) { row ->
                ContentCard { LabeledValue(row.value.first, "${row.value.second} / ${currentRows[row.index].second}") }
            }
            item { Banner("Se reemplazarán todos los datos locales y ajustes por los del respaldo. Esta acción no combina registros. Las claves de emparejamiento y la llave API de Claude del dispositivo se conservan.", tone = Tone.Warning) }
            if (review.current.sessionInProgress) item { Banner("Hay una sesión en curso. Termínala o ciérrala antes de restaurar.", tone = Tone.Warning) }
            item { QuietAction("Restaurar y reemplazar datos", { confirming = true }, enabled = !ui.busy && !review.current.sessionInProgress,
                destructive = true, fill = true) }
            item { SecondaryAction("Cancelar", onCancel, enabled = !ui.busy) }
        }
    }
    if (confirming && ui.review != null) AlertDialog(
        onDismissRequest = { confirming = false }, title = { Text("¿Reemplazar todos los datos?") },
        text = { Text("Se perderán los datos actuales que no estén en este respaldo. Confirma solo después de revisar la comparación.") },
        confirmButton = { QuietAction("Sí, restaurar y reemplazar", { confirming = false; onConfirm() }, destructive = true) },
        dismissButton = { QuietAction("Cancelar", { confirming = false }) })
}
