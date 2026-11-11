# 0005: Offline persistence stays on atomic DataStore snapshots; Room waits for queryable history

Status: accepted for ENT-07; emulator evidence only. Its "no network permission" clause was superseded on 2026-11-10 by [decision 0009](0009-in-app-agent.md): the app now declares `INTERNET` for in-app week generation, and nothing else.

ENT-07 requires that no confirmed record is lost to a forced close or missing network. Its issue suggests Room, but the issue marks proposed technical details as needing design.

## Decision

Keep the live session in one atomic DataStore snapshot (`workout.json`) and finished sessions in the independent, idempotent `history-v1.json`. Do not introduce Room in this delivery.

The guarantees come from the existing write path, not from the storage engine:

- Every command (in-app, notification or overlay) is applied under one mutex and written atomically (temp file + rename) **before** the new state is published. A set the UI shows as recorded is already on disk. A failed write publishes nothing.
- Restore validates each snapshot against its own plan. A corrupt or future-version file raises an error and is never overwritten, reset or replaced by an empty default.
- Recording cannot depend on connectivity: network code exists only in the `agent` package (decision 0009), and the app requests no connectivity-state permission (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`). `allowBackup` stays false for local-only data. A unit test guards these properties. Originally the app declared no network permission at all.

## Why not Room now

Room would add a schema, migrations and a KSP toolchain without strengthening this criterion. Atomic whole-file replacement is already crash-consistent. Room becomes worth it when history must be **queried**: comparisons between exposures, charts and adherence (ANA-01..05). It also becomes necessary once `history-v1.json` is large enough that rewriting it on each archive is costly. That migration should import `history-v1.json` idempotently by session ID and keep the file until import is verified.

## Consequences

Each archive rewrites the history file, which is fine at personal scale (a few MB per year). Revisit with ANA-01.

## Verification

`OfflinePersistenceTest` covers the manifest guard, corrupt live/history files being left untouched, and on-disk state matching every published set. `scripts/verify_offline_durability.py` runs in airplane mode and force-kills the process right after each **Completar serie** tap, alternating an immediate kill with a kill after UI confirmation. It checks after every relaunch that no confirmed set was lost and no storage error appears. See [planned recording](../../PLANNED_RECORDING.md).
