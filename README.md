# Workout Notification PoC

A native, self-contained, offline Android demo: 3 exercises, 7 sets, and 10-second rest periods. Built with Kotlin, Compose Material 3, RemoteViews, WindowManager, StateFlow, and DataStore. No backend, Internet access, or sensor permissions.

Version 1.0.1 adds foreground-priority notification actions, a shared publisher that avoids duplicate updates, and a control-latency test. Diagnosis and Samsung validation limitations: [docs/NOTIFICATION_LATENCY.md](docs/NOTIFICATION_LATENCY.md).

## Open, build, and install

Open this folder as a project in Android Studio. Use JDK 17, Android SDK Platform 36, Build Tools 35.0.0, and Platform Tools. The project uses `minSdk=26`, `compileSdk=36`, and `targetSdk=36`, with Gradle Wrapper 8.13, AGP 8.13.0, and Kotlin/Compose compiler 2.2.20. Dependencies are pinned in `app/build.gradle.kts`. The first build downloads dependencies; the installed app works offline.

Set `ANDROID_HOME`, or set `sdk.dir` in `local.properties` to your SDK location. Do not copy `local.properties` from another machine.

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.workoutpoc/.MainActivity
```

On Windows, use `gradlew.bat` and PowerShell. APK location: `app/build/outputs/apk/debug/app-debug.apk`.

In the development environment, a local JDK and SDK were prepared in `.tools/jdk` and `.sdk`, which are excluded from version control. An AVD named `Workout_API_36` stores its data in `.tools/avd`. The following commands automatically select these tools when available:

```powershell
.\scripts\dev.ps1 check
.\scripts\dev.ps1 test
.\scripts\dev.ps1 build
.\scripts\dev.ps1 emulator
.\scripts\dev.ps1 install
.\scripts\dev.ps1 run
.\scripts\dev.ps1 logs
```

Do not start a second instance of the same AVD while it is running. With multiple devices, use `adb -s SERIAL ...` and `ANDROID_SERIAL` for Gradle. Android Studio is optional for CLI development; the IDE was not installed in this environment.

## Try the demo

The app's current interface is in Spanish. The instructions below include the exact button labels.

1. Tap **Start demo — Notification** (`Iniciar demo — Notificación`) and grant notification permission on Android 13+. If you deny it, you can still log sets in the Activity; mode A requires enabling notifications in Settings. The interactive workout channel (`Entrenamiento interactivo`) may also be disabled separately.
2. Open another app, then open the notification shade and expand the notification. Adjust weight in 2.5 kg steps, repetitions one at a time, and RIR from 0 to 4+. These controls use RemoteViews and do not open the Activity.
3. Set 32.5 kg, 7 reps, and RIR 1, then tap **Complete set** (`Completar serie`). The same notification switches to rest, with a countdown, **+10 s**, and **Skip rest** (`Saltar descanso`). The next set of the same exercise proposes the last recorded weight; reps and RIR return to their planned values.
4. **Edit** (`Editar`) in the compact notification directly opens a bottom sheet in the Activity. If the device manufacturer clips the expanded notification layout, use this sheet or the overlay for the same adjustments.
5. In the Activity, tap **Start demo — Floating bubble** (`Iniciar demo — Burbuja flotante`). Only then is permission to display over other apps requested. Grant access and return to the app. If permission is denied, the app explains the limitation and does not create the window.
6. The panel's controls are at least 48 dp. Drag it by its header. **−** collapses it into a draggable circle; tap the circle to expand it. **×** closes only the panel. The workout and notification continue.
7. Both mode buttons work during a session without restarting it. Notification mode closes the overlay; floating-bubble mode also keeps the same notification available so you can compare synchronization.
8. The final set leads directly to **Workout completed** (`Entrenamiento terminado`). The Activity shows every record with exercise, set number, weight, reps, RIR, and completion time. **Undo last set** (`Deshacer última serie`) reopens the last record; **Finish workout** (`Terminar entrenamiento`) preserves a partial summary; **Reset demo** (`Reiniciar demo`) clears the session and returns to the start screen.

## Architecture and guarantees

- `WorkoutReducer` implements pure transitions between Idle, ActiveSet, Resting, and Completed. It validates limits and prevents zero-rep completion, invalid advances, and invalid undo operations. `FinishWorkout` is an additional event for ending a session early.
- `WorkoutRepository` has one instance per Application. A `Mutex` serializes restoration, commands, and timer reconciliation. State is published only after it has been persisted. All interfaces observe the same `StateFlow`.
- Each command includes the session, set index, and revision. Duplicate or stale actions cannot apply to the next set. Very rapid taps against an already-replaced revision may be ignored; wait for the visual update.
- DataStore saves an atomic JSON snapshot containing edited values, records, timestamps, and rest state. Weights use integer tenths of a kilogram to avoid floating-point errors.
- Rest timing uses `elapsedRealtime`, not tick counting. The boot identifier is persisted; after a device reboot, the wall-clock deadline is used as a fallback. Changing the system clock after a reboot may affect that recovery.
- `WorkoutOverlayService` supports both modes, hosts the optional window, and reconciles rest deadlines. It uses `START_NOT_STICKY` and stops when the workout ends or the demo is reset. Broadcasts do not launch Activities or services.
- The overlay is removed while the screen is off or locked and restored after unlocking while the service remains alive. It does not use lock-screen overlay flags, the Bubbles API, or conversations.

## Foreground service and Android 16

A single foreground service (FGS) keeps workout interaction and rest handling available while another app is in use. The user explicitly starts it from a visible Activity. The manifest declares the official `specialUse` type, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_SPECIAL_USE`, with an explanation of the interactive session in `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. It is not a `health` service, does not access sensors, and does not misuse media or synchronization types. It does not start at device boot or automatically restart from the background.

`specialUse` covers valid use cases not covered by the other service types; Google Play acceptance requires review of the use case. This project provides a debug APK for direct installation and does not assume distribution approval. Reference: [official FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

PendingIntents are explicit and immutable. The receiver and service are not exported; only the launcher Activity is exported. Notification permission and special overlay access are handled separately. Denying notifications may hide the notification from the shade even though Android still lists the service in its task manager.

## Tests and evidence

```sh
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

Unit tests cover values and limits, set recording, monotonic rest timing, exercise transitions, all seven sets, duplicate prevention, undo, reset, write failures, and file restoration. Host JVM file tests on Windows use DataStore's official Okio backend because FileStorage's `File.renameTo` cannot replace an existing file on Windows ([Android issue 203087070](https://issuetracker.google.com/issues/203087070)). The app uses Android FileStorage; its persistence is also checked on the emulator.

`WorkoutDeviceTest` uses UIAutomator against real SystemUI buttons and the actual WindowManager overlay. It does not replace notification taps with fabricated broadcasts. Permissions are granted through the shell during automated test setup. Results and observed limitations are documented in [docs/VERIFICATION.md](docs/VERIFICATION.md).

## Experiment limitations

- The expanded notification provides weight, reps, RIR, and completion controls within 232 dp; the compact view uses 48 dp for completion and editing. Android decorates and constrains RemoteViews ([documentation](https://developer.android.com/develop/ui/views/notifications/custom-notification)). If a manufacturer clips controls, the bottom sheet and overlay offer the same operations. Not every font or display configuration is guaranteed to fit all controls.
- There is no exact alarm or wake lock. If the CPU is suspended, the transition may run after the device wakes. It is calculated from the actual deadline and does not drift because ticks were missed. A native countdown may reach zero before the process runs again.
- Android or the user may terminate the process or service. Indefinite background execution is not guaranteed. Returning to the app restores the session and reconciles rest state; tap a mode button to reactivate the service. Force-stop removes the UI surfaces but does not delete the session file.
- Android can hide overlays on sensitive screens. The controls must respect that protection. The panel does not take input focus or open a keyboard.
- Special overlay access can be revoked outside the app. The controller removes the window and logs recoverable errors. If the session file is corrupt, the app shows an error without silently overwriting records. Recovering this self-contained demo may require clearing its app data in Settings.
- Editable routines, session history, synchronization, alarms, sensors, and store publication are outside the scope of this demo.

The initial project folder did not contain a `.coding_practices` directory.
