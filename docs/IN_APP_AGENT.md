# In-app agent: weeks and the next 12-week cycle

Import and activate the first 12-week plan manually. From then on, the app can ask Claude for each week and for the next 12-week cycle with your own API key. The result is a pending proposal: you still review and approve it in **Propuestas de semana**. Design and rationale: [decision 0009](architecture/decisions/0009-in-app-agent.md).

## Set up the key

1. Create an API key in the Claude console (platform.claude.com, **API keys**). It is billed to that account, separately from a Claude subscription.
2. In the app, open **Ajustes** (the gear). Under **Agente** there is a blank **Llave API** field: paste the key and tap **Guardar llave**. The field stays there, blank, to replace the key later (**Reemplazar llave**). The same field appears in **Agente › Generar siguiente semana** while no key is saved.

The key is stored encrypted on the device (Android Keystore) and shown only as its last four characters. It is not included in exports or backups. **Quitar llave** deletes it; uninstalling the app does too. The pairing key of **Envíos firmados** is a different thing and is refused here.

## Generate a week

You need an active plan (**Agente › Planes de periodización**). Only the initial plan is created outside the app from your periodization document and imported ([plans](PLAN_OUTLINE.md)).

1. Open **Generar siguiente semana** from **Agente**, or from **Hoy** when every saved week has records.
2. **Semana a generar** shows what the app computed: the week number, its block, whether it is a deload, the reference week and its days without records. The button is available only when that week can be planned; if the week is already in the program or the cycle is complete, the card says so.
3. Optionally write a note for that week (pain, sleep, schedule, your average body weight). It is sent as written.
4. Tap **Generar semana N**. It usually takes one to three minutes; keep the app open. **Cancelar** stops it and saves nothing.
5. If Claude needs something decisive, each question shows exactly three predefined radio-button choices (the recommended one first), plus a fourth radio choice, **Otra**, which opens a free-text field. Nothing is selected by default, and a blank custom answer cannot be submitted. **Enviar respuestas** continues the same request. It can ask at most twice.
6. When the proposal arrives, **Revisar y aprobar** opens **Propuestas de semana**. Approving it adds the week to the program, as with any imported proposal.

## Finish a cycle and generate the next plan

**Hoy** and **Agente** warn during the final week that a new 12-week plan is needed. Finishing the cycle never offers a week 13. After every day of the final week has a record (or an explicit not-performed mark), and no final-week session is running, **Generar nuevo plan de 12 semanas** becomes available. Explicit not-performed marks can also finish the final week: they do not fabricate training sessions or prevent planning the next cycle. Weekly progression still requires real training evidence in its existing brief.

The request uses `prompts/next-cycle.txt`, the previous cycle's structure, saved training history and body measurements. Claude may ask questions, with the same two-round limit. The app accepts only a validated `PlanOutline` with exactly 12 weeks, version 1, a new plan ID and a new program ID. Known archived IDs cannot be reused. An invalid response gets one correction attempt.

The plan is saved for review in **Planes de periodización** and is never activated automatically. Review and activate it explicitly. Old programs, sessions, plans and measurements remain available. A newly activated cycle starts with week 1; that program is created when its first week proposal is approved. The initial setup still requires manual import.

## What is sent, and where

To the Claude API (Anthropic), over HTTPS, only when you tap the button:

- the weekly prompt of [AGENT_PROMPTS.md](AGENT_PROMPTS.md#2-semana-a-semana) plus a short in-app mode;
- the local context: saved programs, recorded sessions, the active plan and earlier cycle outlines, the next-week brief, planning answers, and dated body measurements including unit, method and optional measurement conditions;
- your note for the week and your answers to Claude's questions.

Measurements recorded in **Progreso > Cuerpo** are included in both weekly and next-cycle requests. Muscle mass and lean mass stay distinct; the prompt compares only matching metrics, units and methods. Nothing is sent in the background. Manual copy/file export still uses the existing measurements checkbox.

## What the app accepts

- For weekly generation: only a `WeekProposal` for the program and block the app's own brief names. For the next cycle: only a new 12-week `PlanOutline`, as above.
- It must decode with the strict v1 codec (unknown fields are rejected) and pass the proposal rules without blocking issues. Warnings are kept for your review.
- A reply that fails is sent back once with the reasons. A second failure, a refusal or a reply cut at the output limit ends the generation with nothing saved.
- The app sets the proposal's `importId`, time and `source` (`Claude en la app`). If you reject a proposal and generate again, the new one takes the next version.

## Failures

The message says what happened: a rejected or revoked key, no permission for the model, usage limit, no credit, the service being unavailable, or no connection. None of them changes your data. Recording, history and review never need a connection.

## Model and cost

Requests use `claude-opus-5-5` at high effort, streamed. The cost of one generation depends mostly on how much history the context holds; it is charged to your API account.

## Verification (2026-11-10)

- Unit tests: 174 run, 0 failures, 1 skipped.
  - `WeekGenerationTest`: acceptance and app-side stamping; questions and answers in one conversation; the two-round limit; another block, blocking issues, unknown fields and other package types sent back once and then refused; a cut reply never parsed; version bump; nothing sent when the brief is not `READY`; the shipped prompt equals the documented one.
  - `ClaudeChatTest`, against a local stand-in for the API: model, key, system prompt, effort, fallback and streaming in the request; earlier turns replayed unchanged with their reasoning blocks; a failed turn not kept; rejected key, no credit, rejected request, cut reply and no connection told apart.
  - `ApiKeyTest`: refused mistakes (pairing key, admin key, spaces), hint-only status, an unreadable stored key.
  - `OfflinePersistenceTest`: `INTERNET` is the only network permission, backup stays off, and no source outside the `agent` package references network code.
- `AgentDeviceTest` (API 36 emulator), 2 of 2 passed:
  - the key round-trips through the Android Keystore under its own test entry, is never on disk in clear, and is removed;
  - the SDK runs on Android end to end against a local TLS stand-in: request, streamed reply and a second turn replaying the first.
- By hand on the same emulator: the screen opens from **Agente**, a synthetic key is saved and shown as its last four characters, and **Quitar llave** removes it.
- Not done: a request to the real API (no real key was used), a whole generation driven through the screen, and physical-device testing.

## Follow-up implementation (2026-11-10)

Added final-week and cycle-end notices, next-cycle generation, a separate muscle-mass metric, and measurement context with provenance. The conversational protocol is shared by weekly and cycle generation. New unit coverage checks cycle identity/length, question continuation, invalid and truncated responses, measurement serialization and legacy compatibility, final-week notices and switching to an unpopulated new cycle. Validation: unit suite 188 tests, 0 failures, 1 skipped; `:app:assembleDebug` and `:app:lintDebug` pass (0 lint errors, 72 warnings); the MCP Python suite passes 14/14. No emulator or physical device was connected for this follow-up, and no real API key was used. The new UI flow therefore remains unverified on a device.

Review correction: local session exports now retain their `programId`. Both app-side week references/proposal evidence and MCP reference records filter by cycle so reused day IDs cannot carry progress from an old cycle into a new one. Older results without the optional identity remain readable. Regression tests cover reused IDs and a final week explicitly marked not performed.

Question controls follow-up: predefined planning questions and both AI generation modes share three predefined radio choices plus **Otra**. The AI protocol rejects two or four options and asks for correction rather than inventing or dropping alternatives. Existing stored answers remain unchanged.
