# Running the Headless Executor

The Headless Executor runs Brokk sessions in a server mode, controllable via HTTP+JSON API. It's designed for remote execution, CI/CD pipelines, and programmatic task automation.

## Task List: lifecycle, persistence, and consumer API (developer-facing)

Brokk supports a structured task-list lifecycle intended for programmatic planning and execution workflows. This section documents how task lists are persisted, the recommended consumer APIs for retrieving and running tasks from Java code, and the headless HTTP endpoints you can use to list and execute tasks remotely.

Key facts
- Task lists are persisted inside the session Context as a StringFragment of type `SpecialTextType.TASK_LIST`.
  - Internally the fragment's text is a JSON-serialized `TaskList.TaskListData` object.
  - The fragment uses JSON for storage and is previewed as Markdown in the GUI (via `SpecialTextType.TASK_LIST.renderPreview(...)`).
- Retrieve the canonical, parsed task list from the active session via:
  - `ContextManager.getTaskList()` — returns a `TaskList.TaskListData` (always non-null).
  - Do NOT call `summarizeTaskList(...)` to obtain the canonical task list; `summarizeTaskList` is an internal helper used by the ContextManager when constructing a new task list from raw text.
- Update / persist edits to the task list via:
  - `ContextManager.setTaskListAsync(TaskList.TaskListData data)` — single entry point UI and plugins should call after mutating tasks (e.g., marking done or reordering).
- Execute a task programmatically via:
  - `ContextManager.executeTask(TaskList.TaskItem task)` — this runs the Architect flow for a single task.
    - Important: `executeTask` uses `task.text()` as the LLM prompt. `task.title()` is UI-only metadata and should not be used as the prompt.
    - On success, `executeTask` performs auto-commit behavior (depending on configuration) and marks tasks done in history as appropriate.

Java consumer example
```java
// Get the current task list (always safe, never null)
TaskList.TaskListData tasks = contextManager.getTaskList();

// Iterate and execute the first incomplete task
for (TaskList.TaskItem item : tasks.tasks()) {
    if (!item.done()) {
        // Execute (blocking); the task's text() is used as the LLM prompt
        TaskResult result = contextManager.executeTask(item);
        if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
            // Optionally update the task list UI / persist a change
            var updated = new ArrayList<>(contextManager.getTaskList().tasks());
            // find and set done=true for the executed task id
            for (int i = 0; i < updated.size(); i++) {
                var it = updated.get(i);
                if (it.id().equals(item.id())) {
                    updated.set(i, new TaskList.TaskItem(it.title(), it.text(), true));
                    break;
                }
            }
            contextManager.setTaskListAsync(new TaskList.TaskListData(updated));
        }
        break; // run one task only in this example
    }
}
```

Context-level helpers
- `Context.getTaskListFragment()` returns the optional raw `ContextFragments.StringFragment` for the task list (if present).
- `Context.getTaskListDataOrEmpty()` returns a parsed `TaskList.TaskListData` or an empty list if absent or not yet materialized.
- Use `Context.withTaskList(TaskList.TaskListData)` or `ContextManager.createOrReplaceTaskList(...)` for creating/replacing task lists from task text sources; `createOrReplaceTaskList` is blocking and performs title summarization for each task.

Notes on mutability and history
- Context is immutable; `Context.withTaskList(...)` returns a new Context instance that must be published (pushed) through the ContextManager for the session to persist the change.
- `ContextManager.setTaskListAsync(...)` is the recommended single entry point for UI code to replace the session task list (it pushes a new Context and ensures observers are notified).

## Headless Task API (endpoints)

Headless clients can list tasks and request execution of a task by id. The server exposes session-scoped task endpoints (session must be loaded/selected).

List tasks
- HTTP
  - Method: GET
  - Path: /v1/sessions/{sessionId}/tasks
  - Auth: Authorization: Bearer <token>

Example request (curl)
```bash
curl -sS -X GET "http://localhost:8080/v1/sessions/<session-id>/tasks" \
  -H "Authorization: Bearer <token>"
```

Example response (200 OK)
```json
{
  "tasks": [
    {
      "id": "78978051-9252-4752-9457-375685764353",
      "title": "Implement UserService",
      "text": "Add CRUD operations to UserService and wire to DB.",
      "done": false
    },
    {
      "id": "bd3f7a1a-6f2c-4eab-9d35-7f2a3b7e8c4a",
      "title": "Add tests for UserService",
      "text": "Create unit tests covering happy-path and error handling for UserService.",
      "done": false
    }
  ]
}
```

Execute a task by id
- HTTP
  - Method: POST
  - Path: /v1/sessions/{sessionId}/tasks/{taskId}/execute
  - Auth: Authorization: Bearer <token>
  - Headers:
    - Idempotency-Key: <client-generated-key> (recommended)
    - Content-Type: application/json
  - Body: Task execution options (see example below)

The request body shape matches the internal TaskExecuteRequest used by the headless executor:
- plannerModel (optional): planner model name to use for reasoning (may be null to use session/project defaults)
- codeModel (optional): code model name to use for implementation (may be null to use project default)
- autoCommit (boolean): whether to auto-commit changes
- autoCompress (boolean): whether to auto-compress history after the run
- reasoningLevel / reasoningLevelCode: optional overrides
- temperature / temperatureCode: optional numeric overrides

Example request (curl)
```bash
curl -sS -X POST "http://localhost:8080/v1/sessions/<session-id>/tasks/<task-id>/execute" \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: task-exec-001" \
  -H "Content-Type: application/json" \
  --data '{
    "plannerModel": "gpt-4o",
    "codeModel": "gpt-4o",
    "autoCommit": true,
    "autoCompress": true,
    "reasoningLevel": "DEFAULT",
    "temperature": 0.0
  }'
```

Example response (201 Created)
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "queued"
}
```

Notes
- The headless execution creates a Job (see `/v1/jobs`) that orchestrates the Architect flow for the requested task. The returned `jobId` can be polled via `/v1/jobs/{jobId}` and `/v1/jobs/{jobId}/events`.
- The `execute` endpoint will use the selected session's TaskList to find the `taskId`. If the `taskId` is not found or the session is not loaded, the endpoint will return an appropriate 4xx error.
- `TaskExecuteRequest` fields are optional; when omitted the ContextManager/Service defaults are used.

Event streaming and progress
- After requesting execution, stream events from:
  - `GET /v1/jobs/{jobId}/events?after=0`
- Headless consoles emit TASK_LIST updates as `TASK_LIST_UPDATE` events (for any mutations) and generate the usual LLM token and STATE_HINT events so clients can reconstruct progress and outputs.

## Where to look in code (touch points)

- Persistence and rendering:
  - `ai.brokk.context.SpecialTextType.TASK_LIST` — fragment description, syntax style (JSON), and preview rendering logic.
  - `ai.brokk.context.Context.withTaskList(TaskList.TaskListData)` — serializes the TaskListData to JSON and updates the Context.
  - `ai.brokk.context.Context.getTaskListFragment()` and `getTaskListDataOrEmpty()` — retrieve the raw fragment and the parsed TaskList data respectively.
- Recommended consumer APIs:
  - `ai.brokk.ContextManager.getTaskList()` — returns parsed `TaskList.TaskListData` for the current session.
  - `ai.brokk.ContextManager.setTaskListAsync(TaskList.TaskListData)` — replace and persist the session task list.
  - `ai.brokk.ContextManager.executeTask(TaskList.TaskItem)` — runs Architect on the given task (uses `task.text()` as the prompt), returns `TaskResult`.
- Headless HTTP handlers:
  - `ai.brokk.executor.HeadlessExecutorMain` — contains handlers for session and job endpoints, including session task listing and task execution endpoints.

## Recommended usage patterns

- For UIs and plugins: always call `ContextManager.getTaskList()` to fetch the domain model task list and render it. Use `setTaskListAsync(...)` to persist local edits.
- For automated runners (headless clients): use the session-level endpoints to list tasks and POST `.../execute` to start a job for a specific task id. Track progress via job events and `GET /v1/jobs/{jobId}`.
- For programmatic in-process execution (inside the same JVM): call `contextManager.executeTask(task)` directly; this is the canonical way to run a task when you have access to the ContextManager instance.

## Traceability and audit

- Task lists are part of the session context and therefore included when exporting/importing session zips (`PUT /v1/sessions`) or when downloading a session (`GET /v1/sessions/{sessionId}`).
- Headless job artifacts and event logs are persisted in the JobStore (see `app/src/main/java/ai/brokk/executor/jobs/JobStore.java`) for replay and auditing.

## Summary

- Task lists are first-class, durable data (stored as `SpecialTextType.TASK_LIST` fragments inside the session Context).
- Consumers should use `ContextManager.getTaskList()` for retrieval and `ContextManager.executeTask(TaskList.TaskItem)` to run tasks.
- Headless clients can list and execute tasks via the session-scoped endpoints shown above; execution returns a job id you can poll for events and status.

(See the rest of this document for executor configuration, other job modes, and usage examples.)
