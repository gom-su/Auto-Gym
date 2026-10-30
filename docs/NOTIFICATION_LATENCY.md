# Notification feedback fix — 1.0.1

Reported device: Samsung Galaxy S23 FE / One UI, 1–3 seconds between a button press and visible notification text. This hardware is not connected here; the long Samsung delay was not reproduced or measured locally.

## Findings and changes

1. Notification button PendingIntents used ordinary broadcasts, without `Intent.FLAG_RECEIVER_FOREGROUND`. These are direct user interactions, so they now request foreground receiver priority. This flag affects delivery/scheduling; `FOREGROUND_SERVICE_IMMEDIATE`, already present, only controls initial FGS notification visibility. See [Android's Intent flag contract](https://developer.android.com/reference/android/content/Intent#FLAG_RECEIVER_FOREGROUND).
2. Every successful receiver command posted the notification twice: once from the service's StateFlow observer and again from the receiver. Each path built RemoteViews and recreated the actions. Both now use the same Application-owned publisher, which posts each snapshot once. This reduces unnecessary SystemUI work and package update-rate pressure. The receiver retains the fallback publication for situations where the service is no longer running.
3. The PendingIntent URI identity changed to `interactive-command-v2`. `FLAG_UPDATE_CURRENT` replaces extras, so simply changing the Intent flags could otherwise leave an old PendingIntent in use after an in-place upgrade. Session, set index, revision checks and immutable explicit PendingIntents remain intact.
4. The final notification is explicitly reposted after `stopForeground` removes the active one; deduplication must not suppress that necessary repost.

The persistence guarantee is preserved: DataStore completes its write before publishing state. No debounce, delayed task, optimistic unsaved value, exact alarm or extra service was introduced.

## Measurements

`NotificationLatencyTest` injects real touch events into SystemUI and checks actual displayed weight, repetitions and RIR text. Its measurement loop disables UIAutomator's implicit idle wait, which would otherwise hide latency behind a long synchronization delay. It checks 12 changes and fails if any takes 1 second or more. The seven-set device journey remains a separate regression test.

Baseline on the API 36 emulator: 334–377 ms, mean 356.8 ms. Receiver-to-applied-state logging was approximately 14–31 ms. The first fixed run measured 326–352 ms, mean 338.5 ms. Raw results are in `docs/evidence/notification-latency-before.txt` and `notification-latency-after.txt`; the final build is tested again after the upgrade identity/version change.

These emulator figures do **not** establish the cause or resolution of the reported Samsung 1–3 second delay. Instrumented processes also receive special scheduling treatment in Android, so the regression test cannot validate OEM broadcast scheduling in normal use. Foreground-priority delivery addresses that scheduling risk; deduplication removes a confirmed inefficiency. The replacement APK needs a same-device retest to establish the Samsung outcome.

The final 1.0.1 run (with the versioned PendingIntent identity) measured 345–407 ms, mean 374.2 ms, while Gradle was also running lint. All 12 changes rendered within the 1-second bound. This is not evidence of an emulator speedup over baseline. Publication logs confirm exactly **one** notification post for each of the 12 changed revisions (`notification-publish-counts.json`).

## Notification versus floating panel — measured comparison

Production 1.0.1 on the same Android 16/API 36 emulator, with no runtime performance changes: **24 taps per interface**, eight each for weight, repetitions and RIR. The test alternates notification → overlay → notification → overlay, 12 taps per block, in the same active session. Both surfaces retain their normal DataStore persistence and notification synchronization. The overlay is expanded above the launcher; the notification is expanded in SystemUI. Permissions are pre-granted by the test.

| Tap to observed changed text glyphs | Notification | Floating panel |
|---|---:|---:|
| Mean | 436.8 ms | 260.3 ms |
| Median | 434 ms | 250 ms |
| Minimum–maximum | 378–543 ms | 155–414 ms |
| 95th percentile, nearest rank | 490 ms | 400 ms |
| First 12-tap block mean | 445.1 ms | 252.3 ms |
| Second 12-tap block mean | 428.4 ms | 268.2 ms |

The overlay mean was **40.4% lower** in this run, with overlapping ranges. It was faster on average, but the data does not support calling it instantaneous or guaranteeing every overlay action will beat every notification action.

Both interfaces use the same screenshot sampling and touch injection method; the pixel detector accounts for dark glyphs on the notification's light background and white/lime glyphs on the overlay's dark background. Expected accessibility text and repository values are also checked after every action, separately from the pixel timing. Pressed background colors do not count as changed text. UIAutomator's implicit idle wait is disabled during each measured block. Screenshot acquisition and sampling add overhead, so these are observed upper-bound timings rather than exact panel scan-out measurements. The threshold-based detector, 24-sample size, emulator scheduling and instrumentation limit precision and generalizability. Samsung hardware has not been benchmarked.

Evidence:

- `docs/evidence/feedback-latency-comparison.json`: all 48 per-tap records.
- `docs/evidence/feedback-latency-summary.json`: summary statistics.
- `feedback-latency-comparison.log`: successful device test run, 48 expected results asserted.
- `NotificationLatencyTest.compareNotificationAndOverlayTapToVisibleText`: repeatable instrumented test.

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.workoutpoc.NotificationLatencyTest'
```

## Install and retest on Samsung

### Follow-up: can the remaining ~400 ms be reduced?

The measurement now separates input injection, actual changed text pixels and accessibility text detection. A second thread samples screenshots of the value's bounds while input is injected. It compares dark text glyphs, excluding pressed-background colors; detecting arbitrary changed pixels gave falsely early feedback. Screenshot capture/sampling adds overhead, so these pixel figures are an upper-bound observation, not a precise display-frame timestamp.

With production 1.0.1, 12 taps measured 393–465 ms to changed glyph pixels (mean 432.1 ms), 27–44 ms inside input injection, and 350–448 ms to accessibility text detection. Results: `notification-latency-breakdown-1.0.1.json`. The earlier ~400 ms figure was not solely test overhead; the stricter pixel measurement confirms a similar order of delay in this emulator.

An experiment removed `setSilent(true)`, which adds an implicit group, while keeping a quiet LOW channel. Although Android's GroupCoalescer has a 200 ms minimum linger interval, the experiment did not establish this as the cause here: pixel feedback was 392–453 ms (mean 417.7 ms), a difference too small to claim a meaningful speedup. Results: `notification-latency-breakdown.json`. Both device tests and 13 unit tests passed. This runtime experiment was reverted; the delivered production version remains 1.0.1.

No further application speedup is claimed. App state application/publication was approximately 20–30 ms; most of the observed remainder is downstream in the system notification path. Notification updates are asynchronous, custom RemoteViews are rendered by SystemUI, and there is no public API for this app to directly update the hosted TextViews or force a display frame. Avoiding the durable write would save only a small part of the measured delay while weakening persistence. At this stage the overlay had not been benchmarked; the subsequent matched comparison above provides that measurement.

Sources: [input injection waits for window/surface operations](https://developer.android.com/reference/android/app/UiAutomation#injectInputEvent(android.view.InputEvent,%20boolean)), [implicit silent grouping](https://developer.android.com/reference/androidx/core/app/NotificationCompat#GROUP_KEY_SILENT), and [Android GroupCoalescer source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/coalescer/GroupCoalescer.java).

Install the 1.0.1 APK over the existing debug app; do not clear its data. Reopen it and press **Iniciar demo — Notificación** to recreate the active notification. An existing session resumes. Test weight, repetitions and RIR while another app is visible, then complete a set and test rest actions.

If the delay remains, capture the receiver/application timing alongside the SystemUI messages:

```sh
adb logcat -v monotonic WorkoutNotification:D NotificationService:W '*:S'
```

`received` marks entry to the receiver, `applied` reports time spent applying/persisting the command, and `posted` marks a real notification publication. A large gap before `received` implicates delivery; a large `applied` duration implicates the application/storage path; a fast `posted` followed by visibly late text implicates SystemUI rendering. Device logs and tap timing are necessary to distinguish those cases on Samsung.
