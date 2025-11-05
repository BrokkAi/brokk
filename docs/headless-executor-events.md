# Headless IO Events

Headless execution mode replaces interactive GUI prompts with durable job events that API clients can consume and interpret. Every actionable prompt that would normally block on user input in the desktop application is emitted to the job's event log, ensuring automated pipelines never stall waiting for confirmation.

## Confirmation Prompts (`CONFIRM_REQUEST`)

When internal workflows request a confirmation dialog (for example, via `JOptionPane.showConfirmDialog`), the headless console emits a `CONFIRM_REQUEST` event:

| Field             | Type    | Description                                                                                                                                                     |
|-------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `message`         | String  | Dialog body text. Empty string if no message was provided.                                                                                                      |
| `title`           | String  | Dialog title. Empty string if no title was provided.                                                                                                            |
| `optionType`      | int     | Swing `JOptionPane` option constant that describes the available buttons (e.g., `JOptionPane.YES_NO_OPTION`).                                                   |
| `messageType`     | int     | Swing `JOptionPane` message constant that communicates the severity or purpose (e.g., `JOptionPane.WARNING_MESSAGE`).                                           |
| `defaultDecision` | int     | The numeric button constant automatically selected in headless mode. Clients can reproduce the semantic meaning by comparing against the same Swing constants. |

### Default Decision Policy

Headless mode is non-interactive: `showConfirmDialog` always returns immediately with a pre-selected answer, while logging the request for observability. The policy is:

- `YES_NO_OPTION` and `YES_NO_CANCEL_OPTION` → `YES_OPTION`
- `OK_CANCEL_OPTION` → `OK_OPTION`
- Any other option type → `OK_OPTION`

Automation systems can inspect the emitted event and decide whether to continue with the headless default or take compensating action (e.g., cancel the job) by calling the appropriate API endpoint.

Because every GUI confirmation is surfaced as an event, no hidden modal prompt can block headless execution. Clients should monitor the job event stream and handle `CONFIRM_REQUEST` entries to maintain parity with the desktop experience.
