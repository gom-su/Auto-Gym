# In-app agent: Generar siguiente semana

The app can ask Claude for the next week of the active plan with your own API key. The result is a pending proposal: you still review and approve it in **Propuestas de semana**. Design and rationale: [decision 0009](architecture/decisions/0009-in-app-agent.md).

## Set up the key

1. Create an API key in the Claude console (platform.claude.com, **API keys**). It is billed to that account, separately from a Claude subscription.
2. In the app, open **Agente › Generar siguiente semana** (or **Ajustes › Llave API de Claude**), paste it and tap **Guardar llave**.

The key is stored encrypted on the device (Android Keystore) and shown only as its last four characters. It is not included in exports or backups. **Quitar llave** deletes it; uninstalling the app does too. The pairing key of **Envíos firmados** is a different thing and is refused here.

## Generate a week

You need an active plan (**Agente › Planes de periodización**). The plan itself is still created outside the app from your periodization document and imported ([plans](PLAN_OUTLINE.md)).

1. Open **Generar siguiente semana** from **Agente**, or from **Hoy** when every saved week has records.
2. **Semana a generar** shows what the app computed: the week number, its block, whether it is a deload, the reference week and its days without records. The button is available only when that week can be planned; if the week is already in the program or the cycle is complete, the card says so.
3. Optionally write a note for that week (pain, sleep, schedule, your average body weight). It is sent as written.
4. Tap **Generar semana N**. It usually takes one to three minutes; keep the app open. **Cancelar** stops it and saves nothing.
5. If Claude needs something decisive, its questions appear with options (the recommended one first) and a free answer. **Enviar respuestas** continues the same request. It can ask at most twice.
6. When the proposal arrives, **Revisar y aprobar** opens **Propuestas de semana**. Approving it adds the week to the program, as with any imported proposal.

## What is sent, and where

To the Claude API (Anthropic), over HTTPS, only when you tap the button:

- the weekly prompt of [AGENT_PROMPTS.md](AGENT_PROMPTS.md#2-semana-a-semana) plus a short in-app mode;
- the same context you can export in **Intercambio JSON**: saved programs, recorded sessions, the active plan with its next-week brief, and your planning answers;
- your note for the week and your answers to Claude's questions.

Body measurements are not sent. Nothing is sent in the background.

## What the app accepts

- Only a `WeekProposal` for the program and block the app's own brief names.
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
