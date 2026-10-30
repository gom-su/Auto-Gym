# Verificación del demo

Actualización 1.0.1: se añadieron prioridad de foreground para los broadcasts, publicación deduplicada y un test de latencia. La nueva suite contiene 13 tests unitarios y 2 tests de dispositivo. Véase [NOTIFICATION_LATENCY.md](NOTIFICATION_LATENCY.md) para mediciones y límites de validación en Samsung. El registro de esta actualización es `notification-fix-final.log`; las secciones siguientes describen la verificación inicial del PoC.

Fecha: 30 de octubre de 2026. No había código ni carpeta `.coding_practices` al iniciar.

## Entorno comprobado

| Componente | Resultado |
|---|---|
| JDK | Eclipse Temurin 17.0.20.1, local en `.tools/jdk` |
| Gradle Wrapper | 8.13, JAR incluido y checksum de distribución fijado |
| Android Gradle Plugin | 8.13.0 |
| SDK Platform | Android 36, local en `.sdk` |
| SDK Build Tools | 35.0.0 |
| Platform Tools / ADB | Instalados y funcionando |
| Emulador | 37.2.13, aceleración WHPX operativa |
| AVD | `Workout_API_36`, Android 16/API 36, Google APIs x86_64, perfil Pixel 5 |
| Resolución del AVD | 1080 × 2340, configuración de fuente predeterminada |
| Dispositivo observado | `emulator-5554`, arranque completo y APK instalado/abierto |
| Android Studio | No instalado; se compiló e iteró por CLI |
| Samsung físico | Ninguno conectado; One UI 8.5 pendiente |

El SDK anterior solo tenía plataformas 30–33 y emulador 31.3.13. Se prepararon herramientas actuales dentro del proyecto. El `java.exe` global apuntaba a un shim antiguo; los scripts seleccionan explícitamente el JDK local.

## Comandos ejecutados y resultados

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug connectedDebugAndroidTest --console=plain
```

Resultado final: **BUILD SUCCESSFUL**.

- `WorkoutReducerTest`: **9/9**.
- `WorkoutRepositoryTest`: **4/4**.
- `WorkoutDeviceTest`: **1/1**, recorrido completo mediante controles reales de SystemUI y WindowManager.
- `lintDebug`: **0 errores, 67 advertencias**. Incluyen texto español en recursos sin extraer, versiones estables más nuevas disponibles, metadatos de backup y recomendaciones de layout/RTL. No se desactivaron los errores de lint ni se creó un baseline para ocultarlos.
- `assembleDebug`: APK generado en `app/build/outputs/apk/debug/app-debug.apk`.
- `adb install -r ...`: **Success**.
- Lanzamiento de `.MainActivity`: correcto, sin fallo de inicio observado.

Los informes completos quedan en `app/build/reports/tests/testDebugUnitTest`, `app/build/reports/androidTests/connected/debug` y `app/build/reports/lint-results-debug.html`. Log de la ejecución final: `final-build-verification.log`.

## Recorrido realmente probado en Android 16

La prueba instrumentada inicia el demo desde el botón real de la Activity y cambia a otra aplicación. Busca y pulsa controles reales; verifica después el estado persistido compartido.

| Caso | Evidencia y resultado |
|---|---|
| Notificación expandida: peso, reps y RIR | 30 → 32,5 kg, 8 → 7 reps, RIR 2 → 1, permaneciendo en el cajón. `emulator-final/02-notification-edited.png` |
| Completar desde notificación | Un registro con 32,5 kg, 7 reps, RIR 1; misma notificación en descanso. `03-notification-rest.png` |
| Ampliar/saltar descanso | Deadline ampliado exactamente 10 s y avance único a la segunda serie; conserva 32,5 kg |
| Editar desde compacta | `Editar` abre el panel inferior de la Activity con PendingIntent directo |
| Overlay sobre otra aplicación | Controles reales sobre Reloj; peso a 35 kg y RIR modificado. `04-overlay-over-clock.png` |
| Sincronización bidireccional | El peso del overlay aparece en la notificación; seleccionar RIR 1 en la notificación selecciona RIR 1 en el overlay |
| Burbuja | Contracción, desplazamiento de coordenadas verificado y expansión mediante toque. `05-bubble.png` |
| Completar desde overlay | La notificación cambia a descanso. `06-overlay-updates-notification.png` |
| Descanso automático | Se deja pasar un descanso real de 10 s sin saltarlo y aparece la siguiente serie |
| Bloquear/desbloquear | Overlay ausente al dormir la pantalla y visible al despertar/desbloquear; sin dibujarlo sobre el bloqueo |
| Cerrar solo panel | La sesión permanece ActiveSet; se puede volver a abrir el panel |
| Salir y volver a Activity | Se conservan índice y registros |
| Siete series | Finaliza el plan, se cierra el overlay y el resumen contiene 7 registros; primera serie conserva los valores editados. `07-completed-summary.png` |

Capturas finales: `docs/evidence/emulator-final/`. Las pruebas conceden permisos por shell: **este recorrido automatizado no prueba la interacción humana con los diálogos de permisos**.

## Recuperación tras finalizar el proceso

Se ejecutó además:

```powershell
python scripts/verify_process_restore.py
```

Este script parte de un demo Idle y no borra datos. Edita usando la notificación real, ejecuta `am force-stop`, vuelve a abrir la Activity y comprueba:

1. Restauración exacta de sesión, revisión, peso 32,5 kg, 7 reps y RIR 1.
2. Registro de la serie y restauración de su descanso no vencido, con el mismo deadline.
3. Otra finalización del proceso, espera al vencimiento y reapertura: avanza exactamente a la segunda serie, mantiene el registro y propone 32,5 kg.

Resultado y estado final legible: `docs/evidence/restoration/result.json`. El archivo de DataStore de la aplicación Android se lee mediante `run-as`; no se usa un repositorio falso. Esto complementa los tests JVM, cuyo backend de archivo es Okio por la limitación conocida de FileStorage en Windows.

## Límites observados y pendientes

- Android aplazaba inicialmente la visibilidad de la notificación de FGS. Se corrigió solicitando `FOREGROUND_SERVICE_IMMEDIATE` y se volvió a ejecutar el recorrido completo con éxito.
- Ajustes de Android 16 oculta el overlay; sobre Reloj funciona. Se respeta esa protección. No se intentó eludirla.
- Todos los controles solicitados caben y funcionan en la notificación **expandida del AVD probado**. Ningún ajuste requiere la Activity en esa configuración. La compacta ofrece completar y editar; editar abre el panel inferior. No se infiere el mismo resultado para Samsung, fuentes grandes o pantallas diferentes.
- La prueba de bloquear/desbloquear usa el bloqueo no seguro del AVD. PIN, huella y políticas de privacidad de Samsung quedan pendientes.
- No se probó un S23 FE, One UI 8.5, rotaciones, escalas de fuente grandes, denegación mediante los diálogos reales ni restricciones prolongadas de batería/Doze. Hay tratamiento de esos permisos y documentación, pero no se declara verificación de hardware que no ocurrió.
- El servicio no se reinicia automáticamente si Android lo termina. Al volver a la Activity se restaura la sesión; para reactivar las superficies pulsa un botón de modo. No se garantiza un callback puntual durante suspensión de CPU.
- No hay acceso a Internet, sensores o ubicación en los permisos del APK final. `aapt dump permissions` muestra POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, permisos oficiales de FGS y el permiso interno que añade AndroidX para receivers no exportados.

## Iteración local

`scripts/dev.ps1` selecciona las herramientas locales y permite `check`, `test`, `build`, `emulator`, `install`, `run` y `logs`. El AVD utilizado por las pruebas se inició sin ventana. Para abrir ese mismo AVD con interfaz, detén primero la instancia de pruebas:

```powershell
.\.sdk\platform-tools\adb.exe -s emulator-5554 emu kill
.\scripts\dev.ps1 emulator
.\scripts\dev.ps1 install
.\scripts\dev.ps1 run
```

No ejecutes `emu kill` contra un dispositivo físico. Para hardware Samsung habilita depuración USB, autoriza el ordenador y comprueba el serial con `adb devices -l` antes de instalar.
