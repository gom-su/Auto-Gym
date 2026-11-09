# Design system

All Compose screens share one theme and one small component set, in `app/src/main/java/com/example/workoutpoc/ui/`. A screen describes its content with these components rather than styling its own buttons, cards or colors, so every screen looks and behaves like the same app.

## Foundations (`ui/theme/Theme.kt`)

- **Color.** A fixed brand palette: lime accent `#B4EE80` on a deep green-black surface `#101612` in dark mode, with a light counterpart at accessible contrast (primary `#3D6A1F`). The app follows the system light/dark setting; system-bar icons follow it too (`SystemBarStyle.auto`). Dynamic color is off on purpose, because the notification and floating panel use fixed brand colors.
- **Extended colors.** `AppTheme.extended` adds `success` and `warning` roles, which Material 3 does not name. Charts use `tertiary` for deload weeks.
- **Type.** Roboto with firmer weights for headings and labels. Numbers that change (loads, reps, timers, counts) use `TextStyle.tabular()` so digits do not jitter.
- **Shape.** 6 / 10 / 16 / 20 / 28 dp corners (extra small to extra large). Cards use medium (16), the workout focus card uses extra large (28).
- **Spacing.** A 4 dp grid (`Spacing.xs`…`xxl`), a 16 dp screen gutter, and content capped at 720 dp wide on tablets and in landscape.

## Components (`ui/Components.kt`, `ui/AppIcons.kt`, `ui/Tone.kt`)

| Component | Use |
|---|---|
| `AppScreen` | Every secondary screen: top app bar with an Up arrow and the title, a fixed-height progress track while busy, the screen's status message pinned below the bar (`StatusArea`), and a lazy list with standard gutters. The bar tints when content scrolls under it. |
| `SectionHeader`, `Eyebrow`, `SupportingText` | Heading hierarchy: section titles are announced as headings, eyebrows label groups, supporting text is muted so it never competes with data. |
| `Banner` | Inline messages and the pinned status message. Tone (info, success, warning, error) sets color and icon; announced politely to screen readers. `toneOf` derives the tone from the app's message phrasing ("No se pudo…" is an error, "…guardada" is a success). |
| `EmptyState` | "Nothing yet": an outlined, calm placeholder, never styled as an error. |
| `ContentCard` | Data cards: tonal surface, 16 dp padding. |
| `NavRow` | A row that opens something: optional icon, title, supporting line, optional badge, chevron. |
| `StatusPill`, `MetricTile` / `MetricRow` | Colored status labels; at-a-glance numbers. |
| `ChoiceChip`, `ChipGroup` | Multi-option selection that wraps. A selected chip shows a check mark, not only a color. |
| `SegmentedChoice` | Exactly one of a few short options on one line (RIR, rule severity, thresholds, units). |
| `PrimaryAction`, `SecondaryAction`, `TonalAction`, `QuietAction` | Button emphasis levels: one primary per context; quiet actions for low-emphasis or destructive steps (red when destructive). |
| `Breadcrumbs`, `BackLink` | Going up inside a drill-down (program › week › day) without leaving the screen. |
| `AppIcons` | The Material Symbols the app needs, built from path data, so the build needs no icon artifact and works offline. |

## Patterns

- **Navigation.** A bottom bar holds four tabs: **Hoy** (what to do now), **Plan** (programs and the multi-week plan), **Progreso** (Ejercicios, Sesiones, Adherencia, Cuerpo, Estancamiento, switched with chips) and **Agente** (exchange, questions, proposals, plans). Each tab root has the Ajustes gear and no Up arrow. Other screens (the session, Ajustes, the agent screens, Obsidian migration) open full screen over the tabs, without the bar; their Up arrow is announced with its destination ("Volver a Agente"). The system Back closes the top screen, then returns to Hoy, then leaves the app; inside a drill-down it first climbs one level (day → week → program → list). Each tab and screen keeps its scroll and drill-down while you are elsewhere. Navigation lives in `navigation/AppNavigation.kt`; `TabRoot` lets an existing `AppScreen` serve as a tab root.
- **Status messages.** A screen's latest message (saved, exported, failed…) is pinned under the top bar, so it is seen whatever the scroll position, and can be hidden. It appears without animation so content never slides under a finger that is about to tap.
- **Scroll position.** Each detail (a session, an exercise, a proposal, an exchange or migration review) opens at its top; going back to the list restores where the list was. In Plan, opening a program, week or day brings it to the top, just below the tool grid.
- **Hoy.** One card says what to do now: the running session, or the next day with **Entrenar**. Day status in the week strip is spelled out (Hecho, Parcial, Siguiente…), never colour alone. Pendiente only appears when something waits.
- **Session screen.** Before a session, the two logging modes are the main calls to action, the default one from Ajustes first. During a set, they shrink into a compact switch so the set card, including **Completar serie**, fits above the fold. Rest is shown as a countdown ring that animates between ticks.
- **Choices.** Use `SegmentedChoice` when exactly one short option applies and the options fit on one line; use `ChoiceChip` otherwise.
- **Destructive actions.** Deleting a measurement and resetting a demo with recorded sets ask for confirmation and say what will be lost. Closing a planned session needs no confirmation because its sets are archived first.
- **Forms.** Numeric fields open a number keyboard. Fields state their valid range and explain why a save button is disabled when that is not obvious.
- **Accessibility.** Touch targets are at least 48 dp. Steppers announce their new value and their − / + buttons have descriptive labels. Charts carry a content description listing their points. Status is never shown by color alone: pills have text and selected chips have a check mark.

## Native surfaces

The notification (RemoteViews) keeps its tested layout and colors; OEMs clip it differently and its control latency is measured ([NOTIFICATION_LATENCY.md](NOTIFICATION_LATENCY.md)). The floating panel uses rounded tonal controls with a pressed state and a lime primary action. The launcher icon is adaptive (brand background, lime dumbbell) with a monochrome layer for themed icons.

## Tests

Device tests find controls by visible text. Icon-only controls expose their label as a content description, and the shared `clickText` helper (and `WorkoutDeviceTest.button`) fall back to it, so `click("Volver a Agente")` still taps the Up arrow. Keep tested strings when restyling a screen, and keep each planning question's field and buttons inside the card that holds its text ([DEVICE_TESTS.md](DEVICE_TESTS.md)).
