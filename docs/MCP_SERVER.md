# Auto-Gym MCP server (INT-01)

`mcp/auto_gym_mcp.py` is a Model Context Protocol server (stdio, standard-library Python 3.11+). An agent can **read an authorized training context** and **create week proposals**, but it **cannot approve** them. The phone app remains the source of truth; the server only transports, following the architectural rule "La IA propone. El motor determinista valida. La persona usuaria aprueba."

## Permission model

| Capability | How it is limited |
| --- | --- |
| Read | Exactly one file, passed with `--context`. It must be a v1 `TrainingContext` package that you exported and reviewed in the app: **Programas > Intercambio JSON > Preparar contexto local > Exportar**. Body measurements are only in it if you ticked them. The file is never modified, and no other path is readable through the server. |
| Write | Only new files in the `--outbox` directory, one v1 `WeekProposal` package per proposal. Each is written atomically (temporary file + rename) under a fresh `mcp-…` import ID. |
| Approve / activate | Not possible. There is no such tool, and an unknown tool name returns an error. Proposals are imported in the app (**Intercambio JSON**) and stay **Pendiente de tu decisión** in **Propuestas de semana**. There, the deterministic rules (AG-05) and your explicit decision (AG-02) apply. |

## Tools

- **`get_training_context`:** returns the authorized context: programs, typed session results, and phase/goals/rules from your planning answers (AG-01).
- **`create_week_proposal`:** validates the arguments against the v1 contract, then writes the package.
  - Arguments: `proposalId`, `version`, `programId`, `blockId`, `week` (WeekPlan v1), `differences`, `reasons`.
  - Checks: fields, ID format, value ranges and unit/basis pairs; the program and block must exist in the authorized context; no reuse of saved IDs; non-empty texts.
  - Agents should mention each changed exercise by name in `reasons`, so the app can attach the reason to that change (AG-07).
- **`create_plan_outline`** and **`get_next_week_brief`:** create a multi-week plan for the user to activate, and read which week to plan next with its targets and reference records. For a program with an active plan, `create_week_proposal` only accepts the week and block the brief names. See [multi-week plans](PLAN_OUTLINE.md) and the [agent prompts](AGENT_PROMPTS.md).
- **`list_created_proposals`:** lists the files in the outbox. Whether one was approved is only known in the app.

## Setup

1. In the app, export the context file (above) and copy it to your computer.
2. Register the server in your MCP client. Example client configuration:

```json
{
  "mcpServers": {
    "auto-gym": {
      "command": "python",
      "args": ["/path/to/GymLogger/mcp/auto_gym_mcp.py", "--context", "/path/to/auto-gym-interchange-v1.json", "--outbox", "/path/to/auto-gym-outbox"]
    }
  }
}
```

3. Ask the agent to plan. It reads the context and writes proposals to the outbox.
4. Copy a proposal file to the phone, import it in **Intercambio JSON**, and review it in **Propuestas de semana**.

A remote MCP deployment cannot read the phone directly. Moving files between the phone and the server is a manual, user-controlled step. With `--sign-key` (a pairing key generated in the app), every proposal is written as an HMAC-signed envelope, which the app verifies before accepting it. See [signed sync](SIGNED_SYNC.md) (INT-02).

## Verification (2026-11-06)

- `python -m unittest discover -s mcp/tests`: 4 tests pass over the real stdio transport.
  - **No approval:** tools offer no approval, and an approval call is refused.
  - **Read-only context:** the context is readable and unchanged afterwards (hash compared).
  - **Pending proposal:** a valid proposal is written atomically to the outbox as pending.
  - **Rejections:** unknown program or block, reused saved IDs, an incompatible unit/basis, out-of-range RIR, extra fields such as `approve` and empty reasons are all rejected with nothing written.
- `McpInteropTest` (app unit tests, 106 run, 0 failures, 1 skipped): `mcp/examples/week-proposal-from-mcp.json`, produced by the server from the synthetic context, decodes with the app's interchange codec. It passes the app's integrity rules, so it can be approved in the app, and only there.
