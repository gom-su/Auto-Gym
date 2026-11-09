package com.example.workoutpoc.settings

import androidx.compose.runtime.Composable
import com.example.workoutpoc.ui.*

/** Ajustes: device permissions for logging, one-off data tools and the demo, kept out of everyday navigation. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit, notificationEnabled: Boolean, overlayGranted: Boolean,
    onNotificationSettings: () -> Unit, onOverlaySettings: () -> Unit,
    onMigration: () -> Unit, onExchange: () -> Unit, demoAvailable: Boolean, onDemo: () -> Unit,
    loggingMode: LoggingMode = LoggingMode.NOTIFICATION, onLoggingMode: (LoggingMode) -> Unit = {}
) {
    AppScreen("Ajustes", "Volver", onBack) {
        item { SectionHeader("Registro") }
        item {
            ContentCard {
                SegmentedChoice("Registrar con", LoggingMode.entries, loggingMode, { it.label }, onLoggingMode)
                SupportingText("Entrenar empieza la sesión en este modo. Durante la sesión puedes cambiar al otro.", small = true)
            }
        }
        item { NavRow("Notificaciones", onNotificationSettings, icon = AppIcons.Notifications,
            supporting = if (notificationEnabled) "Activadas: registra series desde la notificación"
                else "Desactivadas: la sesión solo se registra en la aplicación") }
        item { NavRow("Panel flotante", onOverlaySettings, icon = AppIcons.Bubble,
            supporting = if (overlayGranted) "Autorizado para mostrarse sobre otras aplicaciones"
                else "Sin permiso para mostrarse sobre otras aplicaciones") }
        item { SectionHeader("Datos") }
        item { NavRow("Migrar desde Obsidian", onMigration, icon = AppIcons.Upload,
            supporting = "Importa y convierte tus semanas de Markdown") }
        item { NavRow("Intercambio JSON", onExchange, icon = AppIcons.Swap,
            supporting = "Archivo de intercambios guardados, exportación e importación") }
        item { SectionHeader("Demo") }
        item { NavRow("Abrir demo", onDemo, enabled = demoAvailable, icon = AppIcons.Play,
            supporting = if (demoAvailable) "Sesión ficticia de 3 ejercicios para probar la notificación y el panel flotante"
                else "Cierra la sesión planificada para usar el demo") }
    }
}
