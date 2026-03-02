// @ts-check
import { renderMarkdownFast } from "../markdown/processor";
import { escapeHtml } from "./util.js";

// ── DOM Elements ─────────────────────────────────────

const messagesEl = document.getElementById("messages");
const MAX_MESSAGES = 200;
const statusBar = document.getElementById("status-bar");
const submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById("submit-btn"));
const cancelBtn = document.getElementById("cancel-btn");

// ── Chat State ───────────────────────────────────────

let isRunning = false;
const SCROLL_THRESHOLD = 100; // px from bottom
let scrollPending = false;
let currentAssistantEl = null;
let replayGeneration = 0;
let currentContentEl = null;
let currentReasoningEl = null;
let reasoningHeader = null;
let accumulatedContent = "";
let accumulatedReasoning = "";
let reasoningStartTime = 0;
let streamRenderTimer = null;
const STREAM_RENDER_INTERVAL_MS = 150;

// ── Reasoning Render State ───────────────────────────

let reasoningRenderTimer = null;
const REASONING_RENDER_INTERVAL_MS = 200;

// ── Elapsed Timer State ──────────────────────────────

let jobStartTime = null;
let elapsedTimerId = null;
let stateHintText = "";
let stateHintClearTimer = null;

// ── Markdown Worker ─────────────────────────────────

/** @type {Worker | null} */
let mdWorker = null;
let renderRequestId = 0;
let pendingContentRenderId = 0;
let pendingReasoningRenderId = 0;
let workerBubbleCounter = 0;

// ── Incremental Rendering State ─────────────────────
// Instead of re-rendering all accumulated markdown on every token,
// we "freeze" completed blocks (paragraphs, code fences, etc.) and
// only re-render the small active tail.
const MIN_FREEZE_SIZE = 1500; // Don't bother splitting until this many chars
let frozenHtml = "";
let freezeCharIndex = 0;
/** @type {HTMLDivElement | null} */
let frozenDiv = null;
/** @type {HTMLDivElement | null} */
let tailDiv = null;

/**
 * Find the last safe split point in markdown text.
 * A "safe" boundary is a blank line where all code fences are closed,
 * meaning everything before it is a complete block that won't change.
 * @param {string} text
 * @returns {number} Character index; everything before it can be frozen.
 */
function findSafeSplitIndex(text) {
  let fenceChar = "";
  let fenceLen = 0;
  let lastSafe = 0;
  let lineStart = 0;

  for (let i = 0; i <= text.length; i++) {
    if (i === text.length || text[i] === "\n") {
      const line = text.substring(lineStart, i);
      const trimmed = line.trimStart();

      if (fenceChar) {
        // Inside a fenced block — look for the closing fence
        const closeMatch = trimmed.match(/^(`{3,}|~{3,})\s*$/);
        if (closeMatch && closeMatch[1][0] === fenceChar && closeMatch[1].length >= fenceLen) {
          fenceChar = "";
          fenceLen = 0;
        }
      } else {
        // Outside a fence — check for an opening fence
        const openMatch = trimmed.match(/^(`{3,}|~{3,})/);
        if (openMatch) {
          fenceChar = openMatch[1][0];
          fenceLen = openMatch[1].length;
        } else if (trimmed === "" && lineStart > 0) {
          // Blank line outside any fence = safe block boundary
          lastSafe = i + 1;
        }
      }

      lineStart = i + 1;
    }
  }

  return lastSafe;
}

/**
 * Lazily create the frozen/tail split divs inside currentContentEl.
 * Called the first time we freeze content during streaming.
 */
function ensureSplitDivs() {
  if (frozenDiv && tailDiv) return;
  if (!currentContentEl) return;

  frozenDiv = document.createElement("div");
  tailDiv = document.createElement("div");

  currentContentEl.innerHTML = "";
  currentContentEl.appendChild(frozenDiv);
  currentContentEl.appendChild(tailDiv);
}

function resetIncrementalState() {
  frozenHtml = "";
  freezeCharIndex = 0;
  frozenDiv = null;
  tailDiv = null;
}

function getWorker() {
  if (mdWorker) return mdWorker;
  // @ts-ignore — injected by panelHtml.ts
  const uri = window.__brokkWorkerUri;
  if (!uri) return null;
  try {
    mdWorker = new Worker(uri);
    mdWorker.onmessage = handleWorkerMessage;
    mdWorker.onerror = (err) => {
      console.error("[Brokk] Markdown worker error:", err);
      mdWorker = null; // Fall back to sync
    };
    return mdWorker;
  } catch (err) {
    console.warn("[Brokk] Could not create markdown worker, using sync fallback:", err);
    return null;
  }
}

/**
 * Captured target element for the pending content render.
 * Needed because currentContentEl/tailDiv may be nulled before the worker responds.
 * @type {HTMLElement | null}
 */
let pendingContentTarget = null;

/** @param {MessageEvent} e */
function handleWorkerMessage(e) {
  const { id, html } = e.data;
  // Content render response
  if (id === pendingContentRenderId) {
    const target = pendingContentTarget;
    if (target) {
      target.innerHTML = html;
      scrollToBottom();
    }
    pendingContentTarget = null;
  }
  // Reasoning render response
  if (id === pendingReasoningRenderId && currentReasoningEl) {
    const contentArea = currentReasoningEl.querySelector(".reasoning-content");
    if (contentArea) {
      contentArea.innerHTML = html;
    }
    scrollToBottom();
  }
}

/**
 * Request an async render from the worker. If the worker isn't available, returns false.
 * @param {string} text
 * @param {"content" | "reasoning"} target
 * @param {HTMLElement} [explicitTarget] - Optional explicit target element (used for finalization when currentContentEl may be nulled)
 * @returns {boolean} true if worker handled it
 */
function requestWorkerRender(text, target, explicitTarget) {
  const worker = getWorker();
  if (!worker) return false;
  const id = ++renderRequestId;
  if (target === "content") {
    pendingContentRenderId = id;
    pendingContentTarget = explicitTarget || tailDiv || currentContentEl;
  } else {
    pendingReasoningRenderId = id;
  }
  worker.postMessage({ id, text, bubbleId: workerBubbleCounter++ });
  return true;
}

// ── Init ─────────────────────────────────────────────

export function initChat() {
  getWorker(); // Eagerly init
}

// ── Streaming Renderer ───────────────────────────────

function scheduleStreamRender() {
  if (streamRenderTimer) return;
  streamRenderTimer = setTimeout(() => {
    streamRenderTimer = null;
    if (!currentContentEl) return;

    const text = accumulatedContent;
    const safeIdx = findSafeSplitIndex(text);

    // Advance the freeze boundary when enough stable content exists
    if (safeIdx > freezeCharIndex && safeIdx >= MIN_FREEZE_SIZE) {
      // Render only the newly-frozen delta (small: typically one block)
      const deltaMd = text.substring(freezeCharIndex, safeIdx);
      const deltaHtml = renderMarkdownFast(deltaMd);
      frozenHtml += deltaHtml;
      freezeCharIndex = safeIdx;
      ensureSplitDivs();
      frozenDiv.insertAdjacentHTML("beforeend", deltaHtml);
    }

    // Render only the active tail (the small bit still being streamed)
    const tailMd = text.substring(freezeCharIndex);

    if (tailDiv) {
      // Incremental mode: worker renders just the tail
      if (!requestWorkerRender(tailMd, "content")) {
        tailDiv.innerHTML = renderMarkdownFast(tailMd);
        scrollToBottom();
      }
    } else {
      // Content still small — full render
      if (!requestWorkerRender(text, "content")) {
        currentContentEl.innerHTML = renderMarkdownFast(text);
        scrollToBottom();
      }
    }
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
    if (!requestWorkerRender(accumulatedReasoning, "reasoning")) {
      const contentArea = currentReasoningEl.querySelector(".reasoning-content");
      if (contentArea) {
        contentArea.innerHTML = renderMarkdownFast(accumulatedReasoning);
      }
      scrollToBottom();
    }
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
    // Reasoning is about to be collapsed anyway, so async render is fine
    if (!requestWorkerRender(accumulatedReasoning, "reasoning")) {
      contentArea.innerHTML = renderMarkdownFast(accumulatedReasoning);
    }
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

function pruneOldMessages() {
  while (messagesEl.childElementCount > MAX_MESSAGES) {
    const first = messagesEl.firstElementChild;
    if (first && first !== currentAssistantEl) {
      first.remove();
    } else {
      break; // don't remove the active streaming element
    }
  }
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
  pruneOldMessages();

  accumulatedContent = "";
  accumulatedReasoning = "";
  reasoningStartTime = 0;
  resetIncrementalState();
}

export function finalizeAssistantMessage() {
  flushStreamRender();
  flushReasoningRender();
  if (accumulatedReasoning && currentReasoningEl && reasoningHeader) {
    finalizeReasoning();
  }
  if (currentContentEl && accumulatedContent) {
    resetIncrementalState();
    // Use worker for final render to avoid blocking the main thread.
    // Capture the target element since currentContentEl will be nulled below.
    const finalTarget = currentContentEl;
    if (!requestWorkerRender(accumulatedContent, "content", finalTarget)) {
      finalTarget.innerHTML = renderMarkdownFast(accumulatedContent);
    }
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
        if (!requestWorkerRender(accumulatedReasoning, "reasoning")) {
          const contentArea = currentReasoningEl.querySelector(".reasoning-content");
          if (contentArea) {
            contentArea.innerHTML = renderMarkdownFast(accumulatedReasoning);
          }
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
        resetIncrementalState();
        if (!requestWorkerRender(accumulatedContent, "content")) {
          currentContentEl.innerHTML = renderMarkdownFast(accumulatedContent);
        }
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
  resetIncrementalState();

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
  const myGeneration = ++replayGeneration;
  if (!entries || entries.length === 0) return;

  dismissWelcome();

  const tasks = [];

  for (const entry of entries) {
    if (entry.isCompressed && entry.summary) {
      tasks.push(() => {
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
      });
    } else if (entry.messages) {
      for (const msg of entry.messages) {
        if (msg.role === "user") {
          tasks.push(() => {
            const el = document.createElement("div");
            el.className = "message message-user";

            const label = document.createElement("div");
            label.className = "message-label";
            label.textContent = "You";
            el.appendChild(label);

            const contentEl = document.createElement("div");
            contentEl.className = "message-content";
            contentEl.textContent = msg.text;
            el.appendChild(contentEl);

            messagesEl.appendChild(el);
          });
        } else if (msg.role === "ai") {
          tasks.push(() => {
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
          });
        } else if (msg.role === "custom") {
          if (msg.text) {
            tasks.push(() => {
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
            });
          }
        }
      }
    }
  }

  const CHUNK_SIZE = 20;
  function flushChunk(index) {
    if (myGeneration !== replayGeneration) return;
    const end = Math.min(index + CHUNK_SIZE, tasks.length);
    for (let i = index; i < end; i++) {
      tasks[i]();
    }
    if (end < tasks.length) {
      setTimeout(() => flushChunk(end), 0);
    } else {
      forceScrollToBottom();
    }
  }
  flushChunk(0);
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
  pruneOldMessages();
  scrollToBottom();
}

/** @param {string} text */
export function addNotification(text) {
  const el = document.createElement("div");
  el.className = "notification";
  el.textContent = text;
  messagesEl.appendChild(el);
  pruneOldMessages();
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
  pruneOldMessages();
  scrollToBottom();
}

export function scrollToBottom() {
  if (scrollPending) return;
  const distFromBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight;
  if (distFromBottom > SCROLL_THRESHOLD) return; // user scrolled up — don't steal focus
  scrollPending = true;
  requestAnimationFrame(() => {
    scrollPending = false;
    messagesEl.scrollTop = messagesEl.scrollHeight;
  });
}

/** Bypasses threshold and pending guards to ensure the view scrolls to the end. */
export function forceScrollToBottom() {
  requestAnimationFrame(() => {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  });
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
