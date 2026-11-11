# Auto-Gym

Auto-Gym is a personal Android training application built with Kotlin. The current implementation includes an offline workout notification/floating-panel proof of concept and a variable-program library. Full history, complete portable plan exchange, analytics, and agent-assisted planning are planned work.

ENT-01 adds a program library, today the **Plan** tab: import a variable program from JSON or pasted text, review its blocks/weeks/days/sets, explicitly save it and browse it offline. See the [contract, fictitious fixture and acceptance steps](docs/PROGRAM_IMPORT.md). The workout notification/overlay still records the fixed demo; imported programs do not activate a week. Physical-device acceptance was waived by the user; evidence is emulator-only.

Track work in the [GitHub Project](https://github.com/users/gom-su/projects/1). See the [roadmap](ROADMAP.md), [design system](docs/DESIGN_SYSTEM.md), [product definition](docs/product/PRODUCT_DEFINITION.md), [backlog](docs/product/BACKLOG.md), and [current implementation gaps](docs/product/IMPLEMENTATION_GAPS.md). The repository is licensed under [MPL-2.0](LICENSE).

The demo uses fixed synthetic workout data. Keep real workout records, body measurements, private documents, and credentials outside version control. Public examples and fixtures must be fictitious. The future external agent proposes plans for deterministic validation and explicit user approval; this project does not build or train AI models or provide clinical functionality.

## Current prototype

A native, self-contained, offline Android demo: 3 exercises, 7 sets, and 10-second rest periods. Built with Kotlin, Compose Material 3, RemoteViews, WindowManager, StateFlow, and DataStore. No backend and no sensor permissions; the only network use is the optional in-app agent described below.

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

## Navigation

The app opens on **Hoy**. It suggests the next day of the current week, in order (A → B → C → D), and **Entrenar** starts it at once in your default logging mode (**Ajustes › Registrar con**: notification or floating panel). The week strip shows each day's status and picks another day. A running session takes over the card (**Volver a la sesión**). **Pendiente** lists what waits for you: proposals to review, unanswered agent questions, the next week to request when every saved week has records, and a measurement reminder after 30 days without one. **Esta semana** shows sessions and cardio against your goals. The current program is the one with the active plan (only one plan is active at a time), otherwise the program of your latest session. A bottom bar switches between **Hoy**, **Plan** (programs, multi-week plans, and one **Importar** for every kind of file: programs, agent files and Obsidian weeks each open in their own review), **Progreso** (Ejercicios with stagnation badges, Sesiones, Adherencia, Cuerpo) and **Agente** (**Generar siguiente semana** with your own Claude API key, then the weekly cycle with an external agent as four steps: send context, answer questions, import the proposal, review and approve; each shows its current state, with the MCP server and manual JSON as equal paths). The session opens full screen; Back returns to Hoy and the session keeps running. The gear on every tab opens **Ajustes**: notification and floating-panel permissions, Obsidian migration, the exchange archive and the demo.

## Try the demo

The app's current interface is in Spanish. The instructions below include the exact button labels.

1. Open **Ajustes** (the gear on any tab) > **Abrir demo**, then tap **Start demo — Notification** (`Iniciar demo — Notificación`) and grant notification permission on Android 13+. If you deny it, you can still log sets in the Activity; mode A requires enabling notifications in Settings. The interactive workout channel (`Entrenamiento interactivo`) may also be disabled separately.
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

### JSON interchange (ENT-02)

Open **Agente > Intercambio JSON** to review, save, copy and import/export versioned context, week proposals and results. Exchanges are archived without approving proposals or modifying programs or the active session. Includes synthetic examples, schema versions, explicit units, measurement privacy and conflict rejection. See the [contract and verification](docs/JSON_EXCHANGE.md). Re-importing a saved program is idempotent: identical packages change nothing, new weeks are appended without duplicates, and conflicts are listed by path before anything is saved (ENT-09, [rules](docs/PROGRAM_IMPORT.md#re-import-and-merge-rules-ent-09)).

### Planned-day recording (ENT-04)

Open the **Plan** tab, choose a saved program day and tap **Entrenar este día**; the session opens. Start it with the notification or floating panel. Each set is prefilled from its prescription and recorded with one tap; the prescription stays separate from recorded values. A session in progress is never replaced, and finished or closed sessions are archived on the device. Planned sessions support warm-up sets, pausing and resuming (rest resumes exactly), cardio entries and partial sessions (ENT-06). They also allow omitting a set, adding or removing an extra set and editing recorded sets with notes (ENT-05). Everything is stored offline and every confirmed set survives a forced close (ENT-07, [decision 0005](docs/architecture/decisions/0005-offline-persistence.md)). See [planned recording](docs/PLANNED_RECORDING.md).

### Session history

Open **Progreso > Sesiones** for every recorded session, newest first: date, program, week and day, status (completed, partial or not performed, never assumed), active time, and completed, omitted, unreached, extra and warm-up sets plus cardio. Filter by program to see only its sessions, with totals for what is listed. Tap a session to see each set's load, reps and RIR, notes, and every earlier version of an edited set. The screen only reads; it never changes recorded data. **Exportar CSV** saves the whole history through the system save dialog (one row per set or cardio entry; columns in [session history CSV](docs/SESSION_HISTORY_CSV.md)).

### Goals and adherence (ANA-04)

Open **Progreso > Adherencia** to see every planned day as completed, partial, not performed or without record (never assumed), with completed, omitted and unreached sets, and optional weekly session and cardio goals. See [adherence](docs/ADHERENCE.md).

### Body weight and composition (ANA-03)

Open **Progreso > Cuerpo** to record weight, muscle mass, body fat, lean mass, waist and other dated measurements with their unit and method. These measurements are included in weekly and next-cycle AI generation. Trends are drawn per method and unit; when different methods (e.g. bioimpedance and DEXA) or units exist for a metric, they stay separate and a warning says they are not directly comparable. See [measurements](docs/BODY_MEASUREMENTS.md).

### In-app agent

With your own Claude API key, **Agente > Generar siguiente semana** sends the local context to the Claude API and archives the answer as a pending week proposal: one button instead of exporting, asking an external agent and importing. The key is stored encrypted on the device; Claude's questions are answered on the same screen; a reply the app cannot validate is never saved; you still approve the week in **Propuestas de semana**. The first 12-week plan is imported manually. The final week shows a notice; after the cycle is recorded, **Generar nuevo plan de 12 semanas** uses its history and body measurements, asks questions if needed and saves the next plan for explicit review and activation. Previous cycles are retained. Recording and review keep working offline. See [in-app agent](docs/IN_APP_AGENT.md) and [decision 0009](docs/architecture/decisions/0009-in-app-agent.md).

### MCP server (INT-01)

`mcp/auto_gym_mcp.py` lets an MCP agent read a context file you exported from the app and write week proposals to an outbox. It cannot approve anything: proposals are imported in the app and stay pending until you decide. See [MCP server](docs/MCP_SERVER.md).

### Signed deliveries (INT-02)

Pair the MCP server with a key generated in **Intercambio JSON > Envíos firmados**; its proposals arrive as signed envelopes that the app verifies (key, signature, type, age, replay) before they can even be previewed, and they still wait for your approval. The network relay is designed but not enabled. See [signed sync](docs/SIGNED_SYNC.md).

### Stagnation signals (INT-03)

In **Progreso > Ejercicios**, exercises with an alert carry an **estancado** badge; the **Señales de estancamiento** row lists alerts raised when recent comparable exposures of an exercise stop improving on its previous best. Each alert states its period, the data used, its limitations and a checkable explanation; without enough evidence, no alert is raised. See [signals](docs/STAGNATION_SIGNALS.md).

### Planning questions (AG-01)

Open **Agente > Preguntas de planificación** to answer, or mark as not available, the gaps that matter for planning (goal, phase, restrictions, availability, unknown equipment). Each question offers three predefined radio-button answers and **Otra** for a custom answer, with no default selection. The same control is used for the agent’s dynamic questions. Nothing is assumed and nothing blocks; answers feed the exported context. See [proposal review](docs/PROPOSAL_REVIEW.md#focused-planning-questions-ag-01).

### Multi-week plans

An agent turns your periodization document into a 12-week **plan**: blocks, phases, deloads, set/rep/RIR targets, cardio and the A–D day distribution. Import it in **Intercambio JSON** and activate it in **Agente > Planes de periodización**. From then on, the exported context tells the agent which week to plan next and its targets. Each week is still a proposal you approve; week 12 ends the cycle. See [multi-week plans](docs/PLAN_OUTLINE.md) and the [agent prompts](docs/AGENT_PROMPTS.md).

### Week proposal review (AG-02, AG-05, AG-06)

Open **Agente > Propuestas de semana** to review week proposals imported through **Intercambio JSON**. Each one shows configurable rule results (blocks and warnings), the changes computed against the base week, and the agent's reasons. It stays pending until you approve or reject it; approval appends the week to the program without replacing anything. See [proposal review](docs/PROPOSAL_REVIEW.md).

### Exercise catalog (ENT-08)

Open **Progreso > Ejercicios** to rename exercises, describe their movement, variant, equipment, usual load basis and machine setup, and see each exercise's history. Identity is the stable exercise ID, so renaming never breaks history and non-equivalent variants stay distinct. See [catalog](docs/EXERCISE_CATALOG.md). Each exercise's history is shown as comparable exposures across weeks and programs, grouped by unit and load basis so units are never mixed (ANA-01, [details](docs/EXPOSURE_COMPARISON.md)). Charts of load, reps, volume and RIR leave omitted sessions as gaps and show weeks you mark as deload distinctly (ANA-02, [charts](docs/PERFORMANCE_CHARTS.md)). Weeks can be compared by program and block (phase); deload weeks are listed but never treated as regressions (ANA-05, [phase comparison](docs/PHASE_COMPARISON.md)).

### Obsidian migration (ENT-03)

Open **Ajustes > Migrar desde Obsidian** to import a Workout v3 Markdown week, review separate objectives/results, notes and ambiguity warnings, and explicitly save an offline migration archive. Original text and source lines are retained; seen warnings remain unresolved data. Reimports preserve saved review without duplicates. A reviewed migration can then be converted into a program week (**Convertir en programa**): only exact values become prescriptions, and everything not converted is listed first. Recorded results are not turned into history. See the [contract, synthetic examples and verification](docs/OBSIDIAN_MIGRATION.md).
