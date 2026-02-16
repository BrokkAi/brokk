"""
Manual smoke test / high-level integration steps.

This test file is intentionally written as a documented manual-smoke guide rather than
an automated unit test. It can be executed by a developer as a pytest-marked docstring,
or simply read to follow the steps to validate end-to-end integration between the
Python TUI (brokk-code) and a local Java executor JAR.

How to run the manual smoke test scenario (manual steps):

1. Build the executor JAR locally (from the `app/` Gradle project):
   - From repository root:
     ./gradlew :app:clean :app:assemble
   - The resulting JAR can typically be found under `app/build/libs/`.

2. Start the TUI against the local executor JAR:
   - In a terminal, run:
     uv run python -m brokk_code.app
     (or set up your environment and run `python -m brokk_code.app`)
   - The TUI will spawn the executor JAR as a subprocess and attempt to connect.

3. Create and manage sessions:
   - In the TUI chat input, type:
     /session new
     This creates a new session on the executor and makes it active.
   - Verify the chat/system notifications show a session created message.
   - Optionally export the session to workspace cache:
     /session export
     Check that the file exists at: <workspace>/.brokk/sessions/<sessionId>.zip

4. List and switch sessions:
   - /session list  (should show the current session ID and any others)
   - /session switch <sessionId>  (switch to another available session)
   - Verify the Context panel updates after switching sessions.

5. Submit a small job (ask a short question) to generate context:
   - Type a prompt such as:
     What is a one-line description of the function `trim()`?
   - Observe token streaming in the ChatPanel and that, after completion, the Context panel
     refreshes to include any new fragments (if the executor provided context snapshots).

6. Trigger edits and undo/redo:
   - Make a small edit that affects context (for example via an agent action or by adding a pasted
     text fragment through the `/paste` feature if available).
   - In the chat, run:
     /undo
     Verify the Context panel reflects the undone change and that the system reports success.
   - Then run:
     /redo
     Verify the Context panel reflects the redo.

7. Failures and diagnostics:
   - If an HTTP endpoint returns NOT_FOUND for undo/redo (older executor), the TUI should surface
     a helpful diagnostic; upgrade executor or fallback to alternate flows as needed.
   - If the executor version is unsupported, the TUI logs or displays a message indicating
     the required protocol version.

Notes for automated test authors:
- When authoring pytest tests that simulate these steps, prefer mocking the executor HTTP API
  using httpx.MockTransport for ExecutorManager unit tests, and create small BrokkApp tests
  that run methods (not spawn subprocesses) to validate command handlers and UI refresh logic.
- Tests that require a real executor should run in CI with a prebuilt JAR and appropriate isolation.

Acceptance criteria for this smoke procedure:
- You can create/list/switch sessions from the TUI against a local executor.
- Submitting a short job updates the Context panel and token counts.
- /undo and /redo result in visible Context panel updates and return status messages.
- Session ZIPs can be exported to the workspace cache (.brokk/sessions).
"""
