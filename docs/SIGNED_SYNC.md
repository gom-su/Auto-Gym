# Signed deliveries and sync design (INT-02)

The design and its rationale are in [decision 0008](architecture/decisions/0008-authenticated-sync.md). This page covers what works today.

## Pairing

In **Agente > Intercambio JSON > Envíos firmados (MCP)**, tap **Generar clave de emparejamiento**:
- **Copiar clave** puts `agk1.<keyId>.<secret>` on the clipboard, flagged as sensitive. Give it to the MCP server with `--sign-key` or `AUTO_GYM_SIGN_KEY`.
- **Revocar** blocks all further deliveries signed with that key.

Keys live in `sync-v1.json`, which is never backed up to the cloud (`allowBackup=false`).

## Receiving a signed proposal

With a key configured, the MCP server writes each proposal as a signed envelope. Paste or import it in **Intercambio JSON**. Before anything is shown, the app verifies it:
- **Key:** paired and not revoked.
- **Signature:** the HMAC-SHA256 covers every field.
- **Type:** only week proposals and multi-week plans (`PlanOutline`) are accepted from outside.
- **Age:** at most 7 days old and at most 5 minutes in the future.
- **Nonce:** not used before.
- **Content:** valid interchange v1 that matches the declared type.

A failure shows **Envío firmado rechazado** with the reason, and nothing is saved. A valid envelope shows **Firma verificada con la clave …**. **Guardar** verifies it again, records its nonce and archives the proposal. The proposal then waits in **Propuestas de semana** for your decision, like any import.

Plain, unsigned packages remain importable: importing one yourself is your own authorization.

## Guarantees

- **No external write without authorization:** unknown, revoked or tampered deliveries, other data types, stale deliveries and replays are all refused. Even accepted deliveries are only *pending proposals*.
- **No lost records on disconnection or conflict:**
  - remote data never overwrites local data;
  - a re-sent delivery is a rejected replay, and nothing changes;
  - the same `importId` with different content is rejected without replacing the stored package;
  - corrupt sync state is never reset.

## Verification (2026-11-06)

- App unit tests: 109 run, 0 failures, 1 skipped. `SignedSyncTest` adds 3 cases:
  - an envelope signed by the Python MCP server verifies in the app (cross-language);
  - unknown or revoked key, altered payload, retargeted key, disallowed or mismatched type, stale and future timestamps, unsupported version and replay are all rejected;
  - acceptance stores once, a replay changes nothing, a signed conflicting `importId` never replaces stored data, and pairing and revocation work.
- `python -m unittest discover -s mcp/tests`: 6 tests pass, including signed output with a valid MAC and refusal of malformed keys at start.
- `SignedSyncDeviceTest` (API 36 emulator) runs through the real screen: generate a key, paste an envelope signed with it, see it verified, save it (nonce recorded, proposal archived), then paste it again and see it rejected with the archive unchanged.

`mcp/examples/test-pairing-key.txt` is a synthetic, test-only key used by the cross-language tests. Never use it for a real pairing. Physical-device testing was not performed.
