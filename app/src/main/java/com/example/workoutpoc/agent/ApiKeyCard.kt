package com.example.workoutpoc.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing

/**
 * The Claude API key: whether one is saved (never the key itself) and the field to set it. Without a key the field is
 * blank and waiting. With [alwaysField] (Ajustes) the blank field stays on screen after saving, to replace the key;
 * elsewhere a saved key only shows its status.
 */
@Composable
fun ApiKeyCard(status: ApiKeyStatus, enabled: Boolean, onSave: (String) -> Unit, onClear: () -> Unit, alwaysField: Boolean = false) {
    ContentCard {
        if (status.present) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Llave guardada", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                StatusPill("termina en ${status.hint}", Tone.Success)
            }
            SupportingText("Cifrada en este dispositivo. No se incluye en exportaciones ni copias de seguridad.", small = true)
        } else {
            SupportingText("Crea una llave en la consola de Claude (platform.claude.com, API keys) y pégala aquí. " +
                "Se guarda cifrada en este dispositivo y solo se usa para generar tus semanas.", small = true)
        }
        if (!status.present || alwaysField) {
            // Kept out of saved state on purpose: the key is never written anywhere but its encrypted file.
            // Keyed by the status, so the field is blank again once a key has been saved or removed.
            var draft by remember(status) { mutableStateOf("") }
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
                label = { Text(if (status.present) "Nueva llave API" else "Llave API") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            TonalAction(if (status.present) "Reemplazar llave" else "Guardar llave", { onSave(draft) },
                enabled = enabled && draft.isNotBlank(), icon = AppIcons.Key)
        }
        if (status.present) QuietAction("Quitar llave", onClear, enabled = enabled, icon = AppIcons.Delete, destructive = true)
    }
}
