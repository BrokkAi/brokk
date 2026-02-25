import * as vscode from "vscode";

function getNonce(): string {
  const chars =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let result = "";
  for (let i = 0; i < 32; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

export function getPanelHtml(webview: vscode.Webview, extensionUri: vscode.Uri): string {
  const cssUri = webview.asWebviewUri(
    vscode.Uri.joinPath(extensionUri, "media", "panel.css")
  );
  const jsUri = webview.asWebviewUri(
    vscode.Uri.joinPath(extensionUri, "media", "panel.js")
  );
  const workerUri = webview.asWebviewUri(
    vscode.Uri.joinPath(extensionUri, "media", "markdown-worker.js")
  );
  const nonce = getNonce();

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy"
    content="default-src 'none'; style-src ${webview.cspSource} 'unsafe-inline'; script-src 'nonce-${nonce}'; worker-src ${webview.cspSource}; connect-src ${webview.cspSource};">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link href="${cssUri}" rel="stylesheet">
  <title>Brokk</title>
</head>
<body>
  <div id="activity-panel" class="activity-panel collapsed">
    <div id="activity-header" class="activity-header">
      <span id="activity-toggle" class="activity-toggle">&#9654;</span>
      <span id="activity-session-name" class="activity-session-name">Session</span>
      <button id="switch-session-btn" class="activity-switch-btn" title="Switch session">&#x21C5;</button>
      <span id="activity-latest" class="activity-latest"></span>
      <span class="activity-header-spacer"></span>
      <button id="undo-btn" class="activity-undo-redo" title="Undo most recent step" disabled>&#x21A9;</button>
      <button id="redo-btn" class="activity-undo-redo" title="Redo most recently undone step" disabled>&#x21AA;</button>
    </div>
    <div id="activity-body" class="activity-body">
      <div id="activity-groups"></div>
    </div>
  </div>
  <div id="settings-overlay" class="settings-overlay hidden">
    <div class="settings-panel">
      <div class="settings-header">
        <span class="settings-title">Settings</span>
        <button id="settings-close-btn" class="settings-close">&times;</button>
      </div>
      <div class="settings-body">
        <div class="settings-field">
          <label for="settings-api-key">Brokk API Key</label>
          <div class="settings-key-row">
            <input type="password" id="settings-api-key" placeholder="brk+..." spellcheck="false" autocomplete="off" />
            <button id="settings-toggle-key" class="settings-toggle-btn" title="Show/hide key">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor"><path d="M8 3C4.5 3 1.7 5.1 0.5 8c1.2 2.9 4 5 7.5 5s6.3-2.1 7.5-5c-1.2-2.9-4-5-7.5-5zm0 8.5c-1.9 0-3.5-1.6-3.5-3.5S6.1 4.5 8 4.5s3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5zm0-5.5c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>
            </button>
            <button id="settings-clear-key" class="settings-toggle-btn" title="Clear key" style="display:none">&times;</button>
          </div>
          <div id="settings-key-hint" class="settings-hint">
            Don't have an account? <a href="https://brokk.ai/signup?utm_source=vscode">Sign up</a>
          </div>
        </div>
        <div class="settings-field">
          <label>Balance</label>
          <div class="settings-balance-row">
            <span id="settings-balance">--</span>
            <a href="https://brokk.ai/dashboard" class="settings-topup">Top Up</a>
          </div>
        </div>
        <div id="settings-message" class="settings-message hidden"></div>
        <div class="settings-actions">
          <button id="settings-save-btn" class="settings-save">Save</button>
        </div>
      </div>
    </div>
  </div>
  <div id="messages">
    <div id="welcome">
      <div class="welcome-title">Start a new conversation to begin coding with Brokk</div>
      <div class="welcome-subtitle">Ask questions, request code reviews, or describe what you'd like to build</div>
      <div class="welcome-links">
        <a href="https://brokk.ai/documentation/introduction">Introduction</a>
        <span class="welcome-links-sep">&middot;</span>
        <a href="https://brokk.ai/documentation/actions-toolkit">Actions Toolkit</a>
        <span class="welcome-links-sep">&middot;</span>
        <a href="https://brokk.ai/documentation/faq">FAQ</a>
      </div>
    </div>
  </div>
  <div id="status-bar" class="hidden"></div>
  <div id="tasklist"></div>
  <div id="token-bar"></div>
  <div id="context-header">
    <span class="context-header-title">Context</span>
    <span id="token-text"></span>
    <button id="attach-btn" title="Attach context">+</button>
  </div>
  <div id="fragments"></div>
  <div id="context-menu" class="context-menu hidden"></div>
  <div id="autocomplete-dropdown" class="autocomplete-dropdown hidden"></div>
  <div id="input-area">
    <div id="input-drag-handle"></div>
    <div id="instructions-header">
      <span class="instructions-header-title">Instructions</span>
      <span id="token-text-instructions"></span>
      <button id="settings-btn" title="Settings">&#9881;</button>
    </div>
    <div class="input-row">
      <textarea id="prompt-input" placeholder="Ask Brokk..." rows="3"></textarea>
    </div>
    <div class="input-footer">
      <div class="model-selectors">
        <div class="custom-select" id="mode-select">
          <button class="custom-select-trigger">
            <span class="custom-select-label">Mode</span>
            <span class="custom-select-value" id="mode-value">Lutz</span>
            <svg class="custom-select-chevron" width="8" height="8" viewBox="0 0 8 8" fill="currentColor"><path d="M0 2l4 4 4-4z"/></svg>
          </button>
          <div class="custom-select-dropdown hidden">
            <div class="custom-select-option selected" data-value="LUTZ">
              <span class="option-label">Lutz</span>
              <span class="option-desc">Gathers context, plans, and executes</span>
            </div>
            <div class="custom-select-option" data-value="CODE">
              <span class="option-label">Code</span>
              <span class="option-desc">Code changes within current context</span>
            </div>
            <div class="custom-select-option" data-value="ASK">
              <span class="option-label">Ask</span>
              <span class="option-desc">Answer questions about current context</span>
            </div>
            <div class="custom-select-option" data-value="PLAN">
              <span class="option-label">Plan</span>
              <span class="option-desc">Gathers context and generates a task list</span>
            </div>
          </div>
        </div>
        <div class="custom-select" id="planner-select">
          <button class="custom-select-trigger">
            <span class="custom-select-label">Primary</span>
            <span class="custom-select-value" id="planner-value">Loading...</span>
            <svg class="custom-select-chevron" width="8" height="8" viewBox="0 0 8 8" fill="currentColor"><path d="M0 2l4 4 4-4z"/></svg>
          </button>
          <div class="custom-select-dropdown hidden">
          </div>
        </div>
        <div class="custom-select" id="code-select">
          <button class="custom-select-trigger">
            <span class="custom-select-label">Code</span>
            <span class="custom-select-value" id="code-value">gemini-3-flash-preview</span>
            <svg class="custom-select-chevron" width="8" height="8" viewBox="0 0 8 8" fill="currentColor"><path d="M0 2l4 4 4-4z"/></svg>
          </button>
          <div class="custom-select-dropdown hidden">
            <div class="custom-select-option selected" data-value="gemini-3-flash-preview"><span class="option-label">gemini-3-flash-preview</span></div>
          </div>
        </div>
      </div>
      <div class="input-buttons">
        <button id="submit-btn" title="Send">
          <svg width="14" height="14" viewBox="0 0 12 12" fill="currentColor"><path d="M2 1v10l9-5-9-5z"/></svg>
        </button>
        <button id="cancel-btn" class="hidden">Cancel</button>
      </div>
    </div>
  </div>
  <script nonce="${nonce}">window.__brokkWorkerUri = "${workerUri}";</script>
  <script nonce="${nonce}" src="${jsUri}"></script>
</body>
</html>`;
}
