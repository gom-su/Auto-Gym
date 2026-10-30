# Workout Notification PoC

Demo Android nativo, cerrado y offline: 3 ejercicios, 7 series, descanso de 10 segundos. Kotlin, Compose Material 3, RemoteViews, WindowManager, StateFlow y DataStore. No tiene backend, acceso a Internet ni permisos de sensores.

Versión 1.0.1: acciones de notificación con prioridad de foreground, publicación compartida sin duplicados y prueba de latencia de los controles. Diagnóstico y límites de validación Samsung: [docs/NOTIFICATION_LATENCY.md](docs/NOTIFICATION_LATENCY.md).

## Abrir, compilar e instalar

Abre esta carpeta como proyecto en Android Studio. Usa JDK 17 y Android SDK Platform 36, Build Tools 35.0.0 y Platform Tools. `minSdk=26`, `compileSdk=36`, `targetSdk=36`. Gradle Wrapper 8.13, AGP 8.13.0, Kotlin/Compose compiler 2.2.20; dependencias fijadas en `app/build.gradle.kts`. La primera compilación descarga dependencias; la aplicación instalada funciona sin conexión.

Configura `ANDROID_HOME` o `local.properties` con `sdk.dir` apuntando a tu SDK. No copies el `local.properties` de otra máquina.

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.workoutpoc/.MainActivity
```

En Windows usa `gradlew.bat` y PowerShell. APK: `app/build/outputs/apk/debug/app-debug.apk`.

En este entorno se prepararon JDK y SDK locales en `.tools/jdk` y `.sdk` (excluidos del control de versiones). Hay un AVD `Workout_API_36`, con datos en `.tools/avd`. Los siguientes comandos seleccionan automáticamente estas herramientas:

```powershell
.\scripts\dev.ps1 check
.\scripts\dev.ps1 test
.\scripts\dev.ps1 build
.\scripts\dev.ps1 emulator
.\scripts\dev.ps1 install
.\scripts\dev.ps1 run
.\scripts\dev.ps1 logs
```

No inicies una segunda instancia del mismo AVD si ya está activo. Con varios dispositivos, usa `adb -s SERIAL ...` y `ANDROID_SERIAL` para Gradle. Android Studio es opcional para iterar por CLI; no se instaló el IDE.

## Probar el demo

1. Pulsa **Iniciar demo — Notificación** y concede notificaciones en Android 13+. Si lo deniegas, puedes registrar desde la Activity; el modo A necesita habilitarlas en Ajustes. También puede estar desactivado el canal «Entrenamiento interactivo».
2. Abre otra aplicación y despliega/expande la notificación. Ajusta peso con pasos de 2,5 kg, reps de una en una y RIR con 0–4+. Todos esos controles son RemoteViews; no abren la Activity.
3. Registra 32,5 kg, 7 reps, RIR 1 y pulsa **Completar serie**. La misma notificación pasa al descanso: cronómetro, **+10 s**, **Saltar descanso**. La siguiente serie del mismo ejercicio propone el último peso registrado; reps y RIR vuelven al plan.
4. **Editar**, en la notificación compacta, abre directamente un panel inferior de la Activity. Si el fabricante recorta el diseño expandido, usa este panel o el overlay para los mismos ajustes.
5. Desde la Activity pulsa **Iniciar demo — Burbuja flotante**. Solo en ese momento se solicita «Mostrar sobre otras aplicaciones». Concede el acceso y vuelve. Si se deniega, se explica y no se crea la ventana.
6. El panel tiene controles de 48 dp o mayores. Arrástralo por su cabecera; **−** lo contrae a un círculo, que se arrastra y se expande con un toque. **×** cierra solamente el panel. El entrenamiento y la notificación continúan.
7. Ambos botones de modo sirven durante una sesión sin reiniciarla. El modo notificación cierra el overlay; el modo burbuja mantiene también la misma notificación para comparar sincronización.
8. La última serie lleva directamente a **Entrenamiento terminado**. La Activity muestra todos los registros con ejercicio, serie, peso, reps, RIR y hora. **Deshacer última serie** reabre el último registro; **Terminar entrenamiento** conserva el resumen parcial; **Reiniciar demo** borra la sesión y vuelve al inicio, donde puedes empezar de nuevo.

## Arquitectura y garantías

- `WorkoutReducer`: transiciones puras entre Idle, ActiveSet, Resting y Completed. Valida límites y bloquea cero reps, saltos y retrocesos indebidos. `FinishWorkout` es un evento adicional para terminar anticipadamente.
- `WorkoutRepository`: una instancia por Application; un `Mutex` serializa restauración, comandos y reloj. Solo publica un estado después de persistirlo. Todas las interfaces observan ese `StateFlow`.
- Cada comando incluye sesión, índice de serie y revisión. Una acción repetida u obsoleta no se aplica a la siguiente serie. Las pulsaciones muy rápidas sobre una versión ya sustituida pueden ser ignoradas: espera la actualización visual.
- DataStore guarda un snapshot JSON atómico con valores editados, registros, marcas de tiempo y descanso. Los pesos son décimas enteras de kg para evitar errores de coma flotante.
- El descanso depende de `elapsedRealtime`, no de contar ticks. Se persiste el identificador de arranque; tras reiniciar el dispositivo se usa la fecha objetivo como alternativa. Cambiar la hora del sistema después de un reinicio puede afectar esa recuperación.
- `WorkoutOverlayService` sirve a ambos modos, aloja la ventana opcional y mantiene la reconciliación del descanso. Usa `START_NOT_STICKY` y se detiene al terminar o reiniciar. Los broadcasts no lanzan Activities ni servicios.
- El overlay se retira mientras la pantalla está apagada o bloqueada y se recupera al desbloquear mientras el servicio siga vivo. No usa flags para mostrarse sobre el bloqueo, Bubbles ni conversaciones.

## Servicio en primer plano y Android 16

Se usa un único FGS porque la interacción y los descansos deben seguir disponibles mientras se usa otra aplicación. El usuario lo inicia expresamente desde una Activity visible. Se declara el tipo oficial `specialUse`, `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_SPECIAL_USE`, con explicación de la sesión interactiva en `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. No es un servicio `health`, no accede a sensores y no usa tipos de medios o sincronización para simular otro caso de uso. No hay arranque al iniciar el teléfono ni reinicio automático desde background.

`specialUse` es un tipo válido para casos que no cubren los demás; su aceptación en Google Play requiere revisión del caso de uso. Este proyecto entrega un APK debug para instalación directa y no presupone aprobación de distribución. Referencia: [tipos oficiales de FGS](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

`PendingIntent` es explícito e inmutable. Receiver y service no están exportados; únicamente la Activity de launcher está exportada. El permiso de notificaciones y el acceso especial de overlay se gestionan por separado. Denegar notificaciones puede ocultar la notificación del cajón aunque Android muestre el servicio en su administrador de tareas.

## Pruebas y evidencia

```sh
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

Los tests unitarios cubren valores y límites, registro, descanso monotónico, cambio de ejercicio, siete series, duplicados, undo, reinicio, fallo de escritura y restauración de archivos. En el JVM Windows se usa el backend oficial Okio de DataStore para las pruebas de archivo: `File.renameTo` de FileStorage no reemplaza archivos existentes en Windows ([incidencia Android 203087070](https://issuetracker.google.com/issues/203087070)). La aplicación utiliza FileStorage de Android; su persistencia también se comprueba en el emulador.

`WorkoutDeviceTest` usa UIAutomator contra botones reales de SystemUI y la ventana real de WindowManager. No sustituye los clics de notificación por broadcasts fabricados. Los permisos se conceden por shell durante la preparación de la prueba automatizada. Los resultados y limitaciones observadas se documentan en [docs/VERIFICATION.md](docs/VERIFICATION.md).

## Límites del experimento

- No se dispone de un Samsung Galaxy S23 FE conectado. La apariencia de One UI 8.5, la agrupación/expansión, fuentes grandes, orientación y políticas de batería Samsung requieren validación en ese hardware.
- La notificación expandida implementa peso, reps, RIR y completar dentro de 232 dp; la compacta usa 48 dp con completar y editar. Android decora y limita RemoteViews ([documentación](https://developer.android.com/develop/ui/views/notifications/custom-notification)). Si el fabricante recorta controles, el panel inferior y overlay ofrecen las mismas operaciones. No se garantiza que cualquier configuración de fuentes o pantalla conserve todos los controles.
- No hay alarma exacta ni wakelock: con la CPU suspendida la transición puede ejecutarse al despertar. Se calcula desde el objetivo real; no se alarga por perder ticks. Un cronómetro nativo puede alcanzar cero antes de que el proceso vuelva a ejecutarse.
- Android o el usuario pueden finalizar el proceso/servicio. No se promete actividad indefinida; al volver se restaura la sesión y se reconcilia el descanso. Pulsa un botón de modo para volver a activar el servicio. Force-stop elimina las superficies; no elimina el archivo de sesión.
- El sistema permite ocultar overlays en pantallas sensibles; los controles no deben usarse para eludir esa protección. El panel no roba el foco ni abre teclado.
- El acceso especial de overlay puede revocarse fuera de la aplicación. El controlador retira la ventana y registra errores recuperables. Si el archivo de sesión está corrupto, la aplicación muestra error sin sobrescribir silenciosamente los registros; para recuperar este demo cerrado puede ser necesario borrar sus datos en Ajustes.
- Sin rutinas editables, historial de sesiones, sincronización, alarmas, sensores ni publicación en tienda.

No existía `.coding_practices` en la carpeta inicial del proyecto.
