# Plan: Native Java ACP Session Support

## Current Gap

Brokk has two ACP server paths:

- `brokk-code/brokk_code/acp_server.py`, using the Python ACP SDK.
- `app/src/main/java/ai/brokk/acp`, the native Java ACP server.

The Python ACP path supports the newer session methods:

- `session/list`
- `session/resume`
- `session/close`
- `session/fork`

The Java ACP server was using the Java SDK annotation helper, which only routed:

- `initialize`
- `session/new`
- `session/load`
- `session/prompt`
- `session/set_mode`
- `session/set_model`
- `session/cancel`

The Java SDK still provides useful transport and JSON-RPC session plumbing, but its high-level schema/annotation layer does not yet expose the newer session methods that the Python SDK exposes.

## Goal

Bring the native Java ACP server to practical parity with the Python ACP server for session lifecycle support while continuing to use the Java SDK transport/session layer.

## Implementation Plan

1. Keep Java SDK transport/session plumbing.
   - Use `StdioAcpAgentTransport`.
   - Use `AcpAgentSession`.
   - Do not use `AcpAgentSupport` for Brokk's native Java ACP server routing.

2. Add Brokk-local ACP DTOs for schema gaps.
   - `SessionCapabilities`
   - `SessionListCapabilities`
   - `SessionResumeCapabilities`
   - `SessionCloseCapabilities`
   - `ListSessionsRequest`
   - `ListSessionsResponse`
   - `SessionInfo`
   - `ResumeSessionRequest`
   - `ResumeSessionResponse`
   - `CloseSessionRequest`
   - `CloseSessionResponse`
   - `ForkSessionRequest`
   - `ForkSessionResponse`

3. Add explicit Java ACP routing.
   - Register request handlers for existing Java SDK methods.
   - Register local handlers for `session/list`, `session/resume`, `session/close`, and `session/fork`.
   - Register cancel as a notification handler.

4. Preserve prompt streaming and permissions.
   - Add a `SyncPromptContext` adapter backed by `AcpAgentSession`.
   - Keep `AcpConsoleIO` behavior unchanged where possible.

5. Implement session behavior.
   - `session/list`: return Brokk sessions from `SessionManager.listSessions()`.
   - `session/resume`: switch to an existing Brokk session and return mode/model state without replay.
   - `session/load`: switch to an existing Brokk session and replay conversation updates.
   - `session/close`: cancel active work for that session and clear ACP in-memory session settings; do not delete the Brokk session.
   - `session/fork`: copy an existing Brokk session, switch to the fork, and return the new session ID.

6. Advertise capabilities.
   - `initialize` should include `loadSession: true`.
   - `initialize` should include `sessionCapabilities.list`, `sessionCapabilities.resume`, `sessionCapabilities.close`, and `sessionCapabilities.fork`.

7. Add tests.
   - Verify `initialize` advertises session capabilities.
   - Verify `session/list` returns session metadata and honors mismatched `cwd`.
   - Verify `session/resume` switches session without replay.
   - Verify `session/close` clears in-memory state and does not delete sessions.
   - Verify `session/fork` copies an existing Brokk session and switches to the fork.
   - Verify explicit runtime routing accepts the new method names.

8. Verify.
   - Compile Java.
   - Run targeted ACP tests.
   - Run broader relevant tests if targeted tests pass.

## Status

- Done: Java ACP server no longer uses `AcpAgentSupport`.
- Done: Explicit routing added through `BrokkAcpRuntime`.
- Done: Local DTOs added in `AcpProtocol`.
- Done: `AcpRequestContext` added to preserve existing prompt streaming.
- Done: `initialize` advertises `sessionCapabilities.list/resume/close/fork`.
- Done: `session/list`, `session/resume`, `session/close`, and `session/fork` implemented.
- Done: Java compile passes.
- Done: Protocol-level tests added in `BrokkAcpAgentTest`.
- Done: Targeted ACP tests pass with `./gradlew :app:test --tests ai.brokk.acp.BrokkAcpAgentTest`.
- Done: Full app test suite passes with `./gradlew :app:test`.
