# 0009: The app can ask Claude for the next week itself, with the user's own API key

Status: accepted on 2026-11-10 at the user's request. It supersedes the "no network permission" clause of [decision 0005](0005-offline-persistence.md); everything else in 0005 stands. Unit and emulator evidence only: no call to the real API was made.

## Problem

Planning a week took a round trip by hand: export the context, give it to an external agent (MCP or pasted JSON), bring the proposal back and import it. The user asked for one button, **Generar siguiente semana**, that applies the weekly prompt to the active plan and the previous week's records.

## Decision

1. **The app calls the Claude API directly**, through the official SDK (`com.anthropic:anthropic-java`), only when the user taps the button. There is no background work, no polling and no telemetry.
2. **The authority boundary does not move.** The model proposes; the deterministic rules validate; the user approves. The generated week is archived as a pending `WeekProposal`, the same package an import produces, and is reviewed and approved in **Propuestas de semana** like any other. Nothing the model returns can approve, activate or change a program.
3. **The key is the user's and stays on the device.** It is encrypted with AES-256-GCM under an Android Keystore key and kept in the no-backup directory. It is never part of an export, the exchange archive, saved UI state or a log. Screens only learn whether a key exists and its last four characters.
4. **What is sent** is the same `TrainingContext` v1 the user can export: programs, recorded sessions, the active plan, the next-week brief and the planning answers. Body measurements are not sent. An optional note typed for that week is sent as written. The screen says so next to the button.
5. **The app decides which week is planned.** It only calls the API when its own brief says `READY`, and it only accepts a proposal for that program and block.
6. **Unacceptable replies are never saved.** A reply must decode with the strict v1 codec and pass the proposal rules without blocking issues. Otherwise it is sent back once with the reasons; a second failure ends the generation with nothing archived. Identity (`importId`), time and `source` are set by the app, and a regenerated proposal takes the next free version.
7. **Questions stay in the app.** The weekly prompt lets the agent ask before proposing. In-app, it returns them as JSON, the user answers on the same screen, and the same conversation continues. At most two rounds; then it must propose.
8. **One prompt, one source.** The system prompt is the weekly prompt of [AGENT_PROMPTS.md](../../AGENT_PROMPTS.md), shipped as an asset and compared with the document by a unit test, plus a short in-app mode (no MCP, JSON-only replies).
9. **Network code lives in one package** (`agent`). A unit test fails if any other source file references the SDK, OkHttp or `java.net`. The app still requests no connectivity-state permission, and cloud backup stays disabled.

Request shape: model `claude-opus-5-5`, effort `high`, streamed, up to 64,000 output tokens, with server-side refusal fallback (`fallbacks: "default"`). Earlier turns are replayed unchanged, reasoning blocks included.

## Consequences

- Recording, history, review and every export still work with no connection. Only the button needs one.
- The user's training data leaves the device when, and only when, they tap the button, and goes to Anthropic under the terms of their own API account. Each generation is billed to that account.
- The debug APK grows from about 13 MB to about 27 MB: the SDK brings OkHttp, Jackson and Kotlin reflection.
- A stolen unlocked device could spend the key but not read it from the app. Revoking it in the Claude console ends that.
- The external paths (MCP server, manual JSON, signed envelopes) are unchanged and remain equal alternatives.
- Not covered: creating the multi-week plan from the periodization document (still done outside and imported), and a closing note when the cycle is complete.

## Verification

See [in-app agent](../../IN_APP_AGENT.md#verification).

## Follow-up: cycle continuity and measurements (2026-11-10)

The user clarified that only the first 12-week plan is imported manually. The final week must warn that a new plan is needed. After its days are recorded and no session remains in progress, the in-app agent can generate the next 12-week plan from accumulated history, ask questions and archive it for explicit review/activation. The new cycle must use new plan/program IDs; the prior cycle remains intact. No week 13 is generated.

This supersedes the measurement exclusion in item 4: weekly and cycle requests now include local dated body measurements with units, method and optional provenance. The body section adds muscle mass separately from lean mass. Manual JSON sharing still uses the existing opt-in checkbox. Context retains inactive/replaced outlines separately from active plans. Week and cycle responses share the conversational validation protocol.
