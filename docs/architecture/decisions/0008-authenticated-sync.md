# 0008: Authenticated synchronization: signed envelopes, device-side authority

Status: accepted for INT-02. The verification core and pairing are implemented. A network transport is designed below and deliberately not enabled: the app still declares no network permission (decision 0005).

## Problem

A remote MCP deployment must exchange data with the phone without reading its local storage. Two failure modes must be handled. A disconnection or conflict must never lose a record. And no external write may be accepted without authorization.

## Decision

1. **The device is the only authority.**
   - It *publishes* user-reviewed snapshots: a `TrainingContext` exported by the user, with measurements opt-in.
   - It *receives* only proposals.
   - Nothing remote can read local stores or write programs, sessions, history, measurements or decisions.
2. **Pairing.** The app generates a per-party key: 256-bit random secret, ID `k-…`, shown once as `agk1.<keyId>.<secret>`. The user copies it into the remote party's configuration (`--sign-key` / `AUTO_GYM_SIGN_KEY`). Keys can be revoked in the app; revocation takes effect on the next delivery.
3. **Signed envelope v1.**
   - Fields: `syncVersion`, `keyId`, `nonce` (128-bit hex), `issuedAt` (UTC ISO-8601), `payloadType`, `payload` (the exact interchange v1 JSON text) and `mac`.
   - The MAC is HMAC-SHA256 over `auto-gym-sync-v1\nkeyId\nnonce\nissuedAt\npayloadType\npayload`. Every field is covered, so nothing can be changed, redirected or retyped.
4. **Inbound authorization**, checked in this order:
   - the key is paired and not revoked;
   - the MAC verifies in constant time;
   - the type is `WeekProposal`, the only type writable from outside;
   - the issue time is within 7 days in the past and 5 minutes in the future;
   - the nonce is unused for that key;
   - the payload is valid interchange v1 and matches the declared type.

   Anything else is rejected with its reason, and nothing is stored. An accepted proposal goes to the exchange archive and is still **pending**: the deterministic rules (AG-05) and explicit approval (AG-02) apply as for any import.
5. **No lost records on disconnection or conflict.**
   - Local data is never overwritten by remote data, because nothing remote can write it.
   - The nonce is recorded atomically on acceptance, before the package is stored. A retried delivery is rejected as a replay, and the remote party re-sends under a new nonce.
   - Packages are idempotent by `importId`: identical content is a no-op, and different content under the same ID is rejected without replacing what is stored.
   - Seen nonces are pruned only after they can no longer verify (older than the 7-day window plus skew).
   - Corrupt sync state is reported and never reset.
6. **Future network transport (not enabled).**
   - Shape: an HTTPS relay with two mailboxes per pairing. *Outbox:* device → relay, signed context snapshots that the device uploads only on explicit user action. *Inbox:* relay → device, signed proposal envelopes.
   - Relay limits: it stores ciphertext-equivalent opaque envelopes, never decides, and never holds device data beyond what the user published.
   - Device-side behavior: the device polls when the user opens Intercambio, applies the same inbound checks, and keeps a persistent upload queue with at-least-once delivery. Upload items are idempotent by `importId`, so a disconnection retries without duplicates.
   - Prerequisite: enabling it requires adding the network permission and revisiting decision 0005.

## Consequences

The only exposure of a key is the user's copy. Losing it compromises at most the ability to *propose*, which still needs the user's approval; revoking the key ends even that. File-based delivery (copying an envelope) already works end to end with the MCP server. See [signed sync](../../SIGNED_SYNC.md).
