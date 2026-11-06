# 0007: MCP is a file-scoped transport, never a source of truth or an approver

Status: accepted for INT-01.

The product requires that an agent can query authorized context and create proposals, but not approve them. A remote MCP server cannot read the phone's local storage.

## Decision

- **Process:** a standalone stdio MCP server (`mcp/auto_gym_mcp.py`, Python standard library only) runs next to the agent, not in the app.
- **Read authority:** exactly one context file the user exported and reviewed in the app. It is the same v1 `TrainingContext` contract, with measurements opt-in.
- **Write authority:** new v1 `WeekProposal` files in one outbox directory, written atomically and validated against the contract and the authorized context.
- **No approval:** there is no tool to approve, activate or modify programs or sessions. The app's existing import → deterministic rules → explicit user approval flow (AG-02/05/06) is the only way a proposal becomes a week.
- **Reused contracts:** the server uses interchange v1 unchanged, so the app needs no new import path. An app unit test decodes and validates a proposal produced by the server.

## Consequences

Moving files between the phone and the agent's machine is manual and under the user's control. Authenticated synchronization that would automate it is designed in INT-02. Revoking access means removing the context file from the server's configuration.

See [MCP server](../../MCP_SERVER.md).
