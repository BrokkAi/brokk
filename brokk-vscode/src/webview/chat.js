// @ts-check
import { renderMarkdownFast } from "../markdown/processor";
import { escapeHtml } from "./util.js";

// ── DOM Elements ─────────────────────────────────────

const messagesEl = document.getElementById("messages");
const statusBar = document.getElementById("status-bar");
const submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById("submit-btn"));
const cancelBtn = document.getElementById("cancel-btn");

// ── Shiki Web Worker ─────────────────────────────────

let shikiWorker = null;
let workerReady = false;
let renderSeq = 0;
/** @type {Map<number, {el: HTMLElement, text: string}>} */
const pendingRenders = new Map();

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

// ── Init ─────────────────────────────────────────────

/**
 * Initialize the Shiki web worker for syntax highlighting.
 * @param {string} workerUrl
 */
export function initChat(workerUrl) {
  if (!workerUrl) return;
  fetch(workerUrl)
    .then((r) => r.text())
    .then((code) => {
      const blob = new Blob([code], { type: "text/javascript" });
      const blobUrl = URL.createObjectURL(blob);
      shikiWorker = new Worker(blobUrl);
      shikiWorker.onmessage = (ev) => {
        const msg = ev.data;
        if (msg.type === "ready") {
          workerReady = true;
          console.log("[Brokk] Shiki worker ready");
          const seqs = [...pendingRenders.keys()].sort((a, b) => b - a);
          for (const seq of seqs) {
            const { el, text } = pendingRenders.get(seq);
            if (!el.isConnected) {
              pendingRenders.delete(seq);
              continue;
            }
            shikiWorker.postMessage({ type: "render", seq, text });
          }
        } else if (msg.type === "result") {
          const pending = pendingRenders.get(msg.seq);
          if (msg.error) {
            console.warn("[Brokk] Worker render error for seq", msg.seq, ":", msg.error);
          } else if (pending && msg.html) {
            if (pending.el.isConnected) {
              pending.el.innerHTML = msg.html;
            }
          }
          pendingRenders.delete(msg.seq);
        } else if (msg.type === "error") {
          console.warn("[Brokk] Shiki worker init error:", msg.message);
        }
      };
      shikiWorker.onerror = (err) => {
        console.warn("[Brokk] Shiki worker error:", err);
      };
    })
    .catch((err) => {
      console.warn("[Brokk] Failed to fetch worker script:", err);
    });
}

// ── Full Render (Shiki Worker) ───────────────────────

/**
 * Request a full render from the Shiki worker.
 * Falls back to fast render if worker isn't available.
 * @param {HTMLElement} el  Target element
 * @param {string} text     Markdown text
 */
function requestFullRender(el, text) {
  const seq = ++renderSeq;
  pendingRenders.set(seq, { el, text });
  if (shikiWorker && workerReady) {
    shikiWorker.postMessage({ type: "render", seq, text });
  } else {
    el.innerHTML = renderMarkdownFast(text);
  }
}

// ── Streaming Renderer ───────────────────────────────

/**
 * Lightweight streaming renderer — replaces the unified/remark pipeline during streaming.
 * @param {string} text  Raw markdown
 * @returns {string} HTML string
 */
function renderStreamingFast(text) {
  const lines = text.split("\n");
  const parts = [];
  let inFence = false;
  let fenceLang = "";
  let codeLines = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (line.startsWith("```")) {
      if (!inFence) {
        inFence = true;
        fenceLang = line.slice(3).trim();
        codeLines = [];
      } else {
        parts.push(
          `<pre><code class="language-${escapeHtml(fenceLang)}">${escapeHtml(codeLines.join("\n"))}</code></pre>`
        );
        inFence = false;
        fenceLang = "";
        codeLines = [];
      }
      continue;
    }

    if (inFence) {
      codeLines.push(line);
      continue;
    }

    if (line.trim() === "") {
      parts.push("<br>");
      continue;
    }

    parts.push(`<p>${formatInline(escapeHtml(line))}</p>`);
  }

  if (inFence && codeLines.length > 0) {
    parts.push(
      `<pre><code class="language-${escapeHtml(fenceLang)}">${escapeHtml(codeLines.join("\n"))}</code></pre>`
    );
  }

  return parts.join("\n");
}

/**
 * Apply inline markdown formatting to an already-escaped HTML line.
 * @param {string} escaped
 * @returns {string}
 */
function formatInline(escaped) {
  escaped = escaped.replace(/`([^`]+)`/g, "<code>$1</code>");
  escaped = escaped.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  escaped = escaped.replace(/\*(.+?)\*/g, "<em>$1</em>");
  return escaped;
}

function scheduleStreamRender() {
  if (streamRenderTimer) return;
  streamRenderTimer = setTimeout(() => {
    streamRenderTimer = null;
    if (!currentContentEl) return;
    currentContentEl.innerHTML = renderStreamingFast(accumulatedContent);
    scrollToBottom();
  }, STREAM_RENDER_INTERVAL_MS);
}

function flushStreamRender() {
  if (streamRenderTimer) {
    clearTimeout(streamRenderTimer);
    streamRenderTimer = null;
  }
}

// ── Reasoning ────────────────────────────────────────

function finalizeReasoning() {
  if (!currentReasoningEl || !reasoningHeader) return;
  const elapsed = ((Date.now() - reasoningStartTime) / 1000).toFixed(1);
  reasoningHeader.textContent = `Thought for ${elapsed}s`;
  reasoningHeader.classList.add("clickable");

  const contentArea = currentReasoningEl.querySelector(".reasoning-content");
  if (contentArea) {
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
  if (accumulatedReasoning && currentReasoningEl && reasoningHeader) {
    finalizeReasoning();
  }
  if (currentContentEl && accumulatedContent) {
    requestFullRender(currentContentEl, accumulatedContent);
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
  if (msg.isReasoning) {
    if (!reasoningStartTime) {
      reasoningStartTime = Date.now();
    }
    accumulatedReasoning += msg.token;
    if (currentReasoningEl) {
      currentReasoningEl.style.display = "block";
      const contentArea = currentReasoningEl.querySelector(".reasoning-content");
      if (contentArea) {
        contentArea.textContent = accumulatedReasoning;
      }
    }
  } else {
    if (accumulatedReasoning && currentReasoningEl && reasoningHeader) {
      finalizeReasoning();
    }
    if (msg.isNewMessage && accumulatedContent) {
      finalizeAssistantMessage();
      startAssistantMessage();
    }
    accumulatedContent += msg.token;
    scheduleStreamRender();
  }
}

/**
 * Clear the chat area and show a notification about what happened.
 * @param {string} [message]
 */
export function resetChat(message) {
  messagesEl.innerHTML = "";

  flushStreamRender();
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
      requestFullRender(contentEl, entry.summary);
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
            rContent.textContent = msg.reasoning;
            reasoningWrap.appendChild(rContent);

            rHeader.addEventListener("click", () => {
              rContent.classList.toggle("collapsed");
              rHeader.classList.toggle("expanded");
            });

            el.appendChild(reasoningWrap);
          }

          const contentEl = document.createElement("div");
          contentEl.className = "message-content";
          requestFullRender(contentEl, msg.text);
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
            requestFullRender(contentEl, msg.text);
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
    requestFullRender(contentEl, content);
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
  const details = document.createElement("details");
  details.className = "command-result";

  const summary = document.createElement("summary");
  summary.className = "command-result-header";
  const icon = msg.success ? "\u2713" : "\u2717";
  const statusClass = msg.success ? "success" : "failure";
  summary.innerHTML = `<span class="command-status ${statusClass}">${icon}</span> <strong>${escapeHtml(msg.stage)}</strong>: <code>${escapeHtml(msg.command)}</code>`;
  if (msg.skipped) {
    summary.innerHTML += ` <em>(skipped: ${escapeHtml(msg.skipReason || "unknown")})</em>`;
  }
  details.appendChild(summary);

  if (msg.output) {
    const body = document.createElement("div");
    body.className = "command-result-body";
    const pre = document.createElement("pre");
    pre.textContent = msg.output;
    if (msg.outputTruncated) {
      pre.textContent += "\n... (truncated)";
    }
    body.appendChild(pre);
    details.appendChild(body);
  }

  if (msg.exception) {
    const errDiv = document.createElement("div");
    errDiv.className = "command-result-error";
    errDiv.textContent = msg.exception;
    details.appendChild(errDiv);
  }

  messagesEl.appendChild(details);
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
