// @ts-check
import { renderMarkdownFast } from "../markdown/processor";
import { escapeHtml } from "./util.js";

// ── DOM Elements ─────────────────────────────────────

const messagesEl = document.getElementById("messages");
const statusBar = document.getElementById("status-bar");
const submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById("submit-btn"));
const cancelBtn = document.getElementById("cancel-btn");

// ── Chat State ───────────────────────────────────────

let isRunning = false;
let currentAssistantEl = null;
let currentContentEl = null;
let currentReasoningEl = null;
let reasoningHeader = null;
let accumulatedContent = "";
let accumulatedReasoning = "";
let reasoningStartTime = 0;
let streamRenderTimer = null;
const STREAM_RENDER_INTERVAL_MS = 80;

// ── Reasoning Render State ───────────────────────────

let reasoningRenderTimer = null;
const REASONING_RENDER_INTERVAL_MS = 120;

// ── Elapsed Timer State ──────────────────────────────

let jobStartTime = null;
let elapsedTimerId = null;
let stateHintText = "";
let stateHintClearTimer = null;

// ── Init ─────────────────────────────────────────────

export function initChat() {
  // No worker initialization needed — highlight.js runs synchronously
}

// ── Streaming Renderer ───────────────────────────────

function scheduleStreamRender() {
  if (streamRenderTimer) return;
  streamRenderTimer = setTimeout(() => {
    streamRenderTimer = null;
    if (!currentContentEl) return;
    currentContentEl.innerHTML = renderMarkdownFast(accumulatedContent);
    scrollToBottom();
  }, STREAM_RENDER_INTERVAL_MS);
}

function flushStreamRender() {
  if (streamRenderTimer) {
    clearTimeout(streamRenderTimer);
    streamRenderTimer = null;
  }
}

// ── Reasoning Render ─────────────────────────────────

function scheduleReasoningRender() {
  if (reasoningRenderTimer) return;
  reasoningRenderTimer = setTimeout(() => {
    reasoningRenderTimer = null;
    if (!currentReasoningEl) return;
    const contentArea = currentReasoningEl.querySelector(".reasoning-content");
    if (contentArea) {
      contentArea.innerHTML = renderMarkdownFast(accumulatedReasoning);
    }
    scrollToBottom();
  }, REASONING_RENDER_INTERVAL_MS);
}

function flushReasoningRender() {
  if (reasoningRenderTimer) {
    clearTimeout(reasoningRenderTimer);
    reasoningRenderTimer = null;
  }
}

// ── Reasoning ────────────────────────────────────────

function finalizeReasoning() {
  if (!currentReasoningEl || !reasoningHeader) return;
  flushReasoningRender();

  const elapsed = ((Date.now() - reasoningStartTime) / 1000).toFixed(1);
  reasoningHeader.textContent = `Thought for ${elapsed}s`;
  reasoningHeader.classList.add("clickable");

  const contentArea = currentReasoningEl.querySelector(".reasoning-content");
  if (contentArea) {
    contentArea.innerHTML = renderMarkdownFast(accumulatedReasoning);
    contentArea.classList.add("collapsed");
  }

  reasoningHeader.addEventListener("click", () => {
    if (contentArea) {
      contentArea.classList.toggle("collapsed");
      reasoningHeader.classList.toggle("expanded");
    }
  });

  accumulatedReasoning = "";
  reasoningStartTime = 0;
}

// ── Elapsed Timer ────────────────────────────────────

export function startJobTimer() {
  jobStartTime = Date.now();
  stateHintText = "";
  statusBar.classList.add("running");
  updateElapsedDisplay();
  elapsedTimerId = setInterval(updateElapsedDisplay, 200);
}

export function stopJobTimer() {
  if (elapsedTimerId) {
    clearInterval(elapsedTimerId);
    elapsedTimerId = null;
  }
  if (stateHintClearTimer) {
    clearTimeout(stateHintClearTimer);
    stateHintClearTimer = null;
  }
  jobStartTime = null;
  stateHintText = "";
  statusBar.classList.remove("running");
}

export function setStateHint(text) {
  stateHintText = text || "";
  if (stateHintClearTimer) {
    clearTimeout(stateHintClearTimer);
  }
  stateHintClearTimer = setTimeout(() => {
    stateHintText = "";
    stateHintClearTimer = null;
    if (jobStartTime) updateElapsedDisplay();
  }, 8000);
  if (jobStartTime) updateElapsedDisplay();
}

function updateElapsedDisplay() {
  if (!jobStartTime) return;
  const elapsed = Math.floor((Date.now() - jobStartTime) / 1000);
  const mins = Math.floor(elapsed / 60);
  const secs = elapsed % 60;
  const timeStr = `${String(mins).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
  let display = `Running... ${timeStr}`;
  if (stateHintText) {
    display += ` \u2014 ${stateHintText}`;
  }
  statusBar.textContent = display;
  statusBar.classList.remove("hidden");
}

// ── Exported Functions ───────────────────────────────

export function startAssistantMessage() {
  dismissWelcome();
  currentAssistantEl = document.createElement("div");
  currentAssistantEl.className = "message message-assistant";

  const label = document.createElement("div");
  label.className = "message-label";
  label.textContent = "Brokk";
  currentAssistantEl.appendChild(label);

  const reasoningWrap = document.createElement("div");
  reasoningWrap.className = "reasoning";
  reasoningWrap.style.display = "none";

  reasoningHeader = document.createElement("div");
  reasoningHeader.className = "reasoning-header";
  reasoningHeader.textContent = "Thinking...";
  reasoningWrap.appendChild(reasoningHeader);

  const reasoningContent = document.createElement("div");
  reasoningContent.className = "reasoning-content";
  reasoningWrap.appendChild(reasoningContent);

  currentReasoningEl = reasoningWrap;
  currentAssistantEl.appendChild(reasoningWrap);

  currentContentEl = document.createElement("div");
  currentContentEl.className = "message-content";
  currentAssistantEl.appendChild(currentContentEl);

  messagesEl.appendChild(currentAssistantEl);

  accumulatedContent = "";
  accumulatedReasoning = "";
  reasoningStartTime = 0;
}

export function finalizeAssistantMessage() {
  flushStreamRender();
  flushReasoningRender();
  if (accumulatedReasoning && currentReasoningEl && reasoningHeader) {
    finalizeReasoning();
  }
  if (currentContentEl && accumulatedContent) {
    currentContentEl.innerHTML = renderMarkdownFast(accumulatedContent);
  } else if (currentAssistantEl && !accumulatedContent && !accumulatedReasoning) {
    currentAssistantEl.remove();
  }
  currentAssistantEl = null;
  currentContentEl = null;
  currentReasoningEl = null;
  reasoningHeader = null;
}

/** @param {object} msg */
export function handleToken(msg) {
  // Respect new-message boundaries for both reasoning and non-reasoning tokens.
  // Without this, turns that begin with reasoning can merge into the previous turn.
  if (msg.isNewMessage && (accumulatedContent || accumulatedReasoning)) {
    finalizeAssistantMessage();
    startAssistantMessage();
  }

  if (msg.isReasoning) {
    if (!reasoningStartTime) {
      reasoningStartTime = Date.now();
    }
    accumulatedReasoning += msg.token;
    if (currentReasoningEl) {
      currentReasoningEl.style.display = "block";
    }
    if (msg.isTerminal) {
      flushReasoningRender();
      if (currentReasoningEl) {
        const contentArea = currentReasoningEl.querySelector(".reasoning-content");
        if (contentArea) {
          contentArea.innerHTML = renderMarkdownFast(accumulatedReasoning);
        }
      }
    } else {
      scheduleReasoningRender();
    }
  } else {
    if (accumulatedReasoning && currentReasoningEl && reasoningHeader) {
      finalizeReasoning();
    }
    accumulatedContent += msg.token;
    if (msg.isTerminal) {
      flushStreamRender();
      if (currentContentEl) {
        currentContentEl.innerHTML = renderMarkdownFast(accumulatedContent);
        scrollToBottom();
      }
    } else {
      scheduleStreamRender();
    }
  }
}

/**
 * Clear the chat area and show a notification about what happened.
 * @param {string} [message]
 */
export function resetChat(message) {
  messagesEl.innerHTML = "";

  flushStreamRender();
  flushReasoningRender();
  currentAssistantEl = null;
  currentContentEl = null;
  currentReasoningEl = null;
  reasoningHeader = null;
  accumulatedContent = "";
  accumulatedReasoning = "";
  reasoningStartTime = 0;

  isRunning = false;
  submitBtn.classList.remove("hidden");
  cancelBtn.classList.add("hidden");
  statusBar.classList.add("hidden");

  if (message) {
    addNotification(message);
  }
}

/**
 * Replay the conversation from the context's task history.
 * @param {{ sequence: number, isCompressed: boolean, taskType?: string, messages?: { role: string, text: string, reasoning?: string }[], summary?: string }[]} entries
 */
export function replayConversation(entries) {
  resetChat();
  if (!entries || entries.length === 0) return;

  dismissWelcome();

  for (const entry of entries) {
    if (entry.isCompressed && entry.summary) {
      const summaryEl = document.createElement("div");
      summaryEl.className = "message message-assistant";

      const label = document.createElement("div");
      label.className = "message-label";
      label.textContent = entry.taskType ? `Brokk (${entry.taskType})` : "Brokk";
      summaryEl.appendChild(label);

      const contentEl = document.createElement("div");
      contentEl.className = "message-content";
      contentEl.innerHTML = renderMarkdownFast(entry.summary);
      summaryEl.appendChild(contentEl);

      messagesEl.appendChild(summaryEl);
    } else if (entry.messages) {
      for (const msg of entry.messages) {
        if (msg.role === "user") {
          addMessage("user", msg.text);
        } else if (msg.role === "ai") {
          const el = document.createElement("div");
          el.className = "message message-assistant";

          const label = document.createElement("div");
          label.className = "message-label";
          label.textContent = entry.taskType ? `Brokk (${entry.taskType})` : "Brokk";
          el.appendChild(label);

          if (msg.reasoning) {
            const reasoningWrap = document.createElement("div");
            reasoningWrap.className = "reasoning";

            const rHeader = document.createElement("div");
            rHeader.className = "reasoning-header clickable";
            rHeader.textContent = "Reasoning";
            reasoningWrap.appendChild(rHeader);

            const rContent = document.createElement("div");
            rContent.className = "reasoning-content collapsed";
            rContent.innerHTML = renderMarkdownFast(msg.reasoning);
            reasoningWrap.appendChild(rContent);

            rHeader.addEventListener("click", () => {
              rContent.classList.toggle("collapsed");
              rHeader.classList.toggle("expanded");
            });

            el.appendChild(reasoningWrap);
          }

          const contentEl = document.createElement("div");
          contentEl.className = "message-content";
          contentEl.innerHTML = renderMarkdownFast(msg.text);
          el.appendChild(contentEl);

          messagesEl.appendChild(el);
        } else if (msg.role === "custom") {
          if (msg.text) {
            const el = document.createElement("div");
            el.className = "message message-assistant";

            const label = document.createElement("div");
            label.className = "message-label";
            label.textContent = "Brokk";
            el.appendChild(label);

            const contentEl = document.createElement("div");
            contentEl.className = "message-content";
            contentEl.innerHTML = renderMarkdownFast(msg.text);
            el.appendChild(contentEl);

            messagesEl.appendChild(el);
          }
        }
      }
    }
  }

  scrollToBottom();
}

export function dismissWelcome() {
  const w = document.getElementById("welcome");
  if (w) w.remove();
}

/**
 * @param {"user" | "assistant"} role
 * @param {string} content
 */
export function addMessage(role, content) {
  dismissWelcome();
  const el = document.createElement("div");
  el.className = `message message-${role}`;

  const label = document.createElement("div");
  label.className = "message-label";
  label.textContent = role === "user" ? "You" : "Brokk";
  el.appendChild(label);

  const contentEl = document.createElement("div");
  contentEl.className = "message-content";
  if (role === "user") {
    contentEl.textContent = content;
  } else {
    contentEl.innerHTML = renderMarkdownFast(content);
  }
  el.appendChild(contentEl);

  messagesEl.appendChild(el);
  scrollToBottom();
}

/** @param {string} text */
export function addNotification(text) {
  const el = document.createElement("div");
  el.className = "notification";
  el.textContent = text;
  messagesEl.appendChild(el);
  scrollToBottom();
}

/** @param {object} msg */
export function addCommandResult(msg) {
  const wrapper = document.createElement("div");
  wrapper.className = "command-result";

  const header = document.createElement("div");
  header.className = "command-result-header";
  const icon = msg.success ? "\u2713" : "\u2717";
  const statusClass = msg.success ? "success" : "failure";
  header.innerHTML = `<span class="command-status ${statusClass}">${icon}</span> <strong>${escapeHtml(msg.stage)}</strong>: <code>${escapeHtml(msg.command)}</code>`;
  if (msg.skipped) {
    header.innerHTML += ` <em>(skipped: ${escapeHtml(msg.skipReason || "unknown")})</em>`;
  }
  wrapper.appendChild(header);

  if (msg.output) {
    const body = document.createElement("div");
    body.className = "command-result-body";
    const pre = document.createElement("pre");
    pre.textContent = msg.output;
    if (msg.outputTruncated) {
      pre.textContent += "\n... (truncated)";
    }
    body.appendChild(pre);
    wrapper.appendChild(body);
  }

  if (msg.exception) {
    const errDiv = document.createElement("div");
    errDiv.className = "command-result-error";
    errDiv.textContent = msg.exception;
    wrapper.appendChild(errDiv);
  }

  messagesEl.appendChild(wrapper);
  scrollToBottom();
}

export function scrollToBottom() {
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

/**
 * Update submit/cancel button visibility and internal running state.
 * @param {boolean} running
 */
export function setButtonState(running) {
  isRunning = running;
  if (running) {
    submitBtn.classList.add("hidden");
    cancelBtn.classList.remove("hidden");
  } else {
    submitBtn.classList.remove("hidden");
    cancelBtn.classList.add("hidden");
  }
}

/** @returns {boolean} Whether a job is currently running. */
export function getIsRunning() {
  return isRunning;
}

/**
 * Update the status bar text and optionally auto-hide after a delay.
 * @param {string} text
 * @param {number} [autoHideMs]  If > 0, hide the status bar after this many ms
 */
export function setStatus(text, autoHideMs) {
  statusBar.textContent = text;
  statusBar.classList.remove("hidden");
  if (autoHideMs > 0) {
    setTimeout(() => statusBar.classList.add("hidden"), autoHideMs);
  }
}
