// @ts-check
import { showMenuAt } from "./util.js";

// ── DOM Elements ─────────────────────────────────────

const fragmentsEl = document.getElementById("fragments");
const tokenText = document.getElementById("token-text");
const tasklistEl = document.getElementById("tasklist");
const contextMenu = document.getElementById("context-menu");

// ── State ────────────────────────────────────────────

/** @type {import('../src/types').ContextFragment[]} */
let currentFragments = [];
/** @type {string | null} */
let hoveredFragmentId = null;
/** @type {ReturnType<typeof acquireVsCodeApi> | null} */
let _vscode = null;

/**
 * Initialize the context module with a vscode API reference.
 * @param {ReturnType<typeof acquireVsCodeApi>} vscodeApi
 */
export function initContext(vscodeApi) {
  _vscode = vscodeApi;
}

// ── Hover Coordination ───────────────────────────────

function setHoveredFragment(id) {
  hoveredFragmentId = id;
  for (const chip of fragmentsEl.children) {
    chip.classList.toggle("dimmed", id !== null && chip.dataset.id !== id);
  }
  const tokenBar = document.getElementById("token-bar");
  for (const seg of tokenBar.querySelectorAll(".token-segment")) {
    seg.classList.toggle("dimmed", id !== null && seg.dataset.id !== id);
  }
}

function clearHoveredFragment() {
  hoveredFragmentId = null;
  for (const chip of fragmentsEl.children) {
    chip.classList.remove("dimmed");
  }
  const tokenBar = document.getElementById("token-bar");
  for (const seg of tokenBar.querySelectorAll(".token-segment")) {
    seg.classList.remove("dimmed");
  }
}

// ── Context Rendering ────────────────────────────────

/**
 * @param {{ fragments: import('../src/types').ContextFragment[], usedTokens: number, maxTokens: number }} ctx
 */
export function renderContext(ctx) {
  currentFragments = ctx.fragments;
  renderTokenBar(ctx.fragments, ctx.maxTokens);
  renderFragments(ctx.fragments);

  const usedK = (ctx.usedTokens / 1000).toFixed(1);
  const maxK = (ctx.maxTokens / 1000).toFixed(0);
  tokenText.textContent = `${usedK}K / ${maxK}K`;
}

/**
 * @param {import('../src/types').ContextFragment[]} fragments
 * @param {number} maxTokens
 */
function renderTokenBar(fragments, maxTokens) {
  const tokenBar = document.getElementById("token-bar");
  tokenBar.innerHTML = "";

  if (maxTokens <= 0 || fragments.length === 0) return;

  for (const frag of fragments) {
    const pct = (frag.tokens / maxTokens) * 100;
    if (pct <= 0) continue;

    const seg = document.createElement("div");
    seg.className = `token-segment segment-${frag.chipKind}`;
    seg.dataset.id = frag.id;
    seg.style.width = pct + "%";
    seg.title = `${frag.shortDescription} (${(frag.tokens / 1000).toFixed(1)}K)`;

    seg.addEventListener("mouseenter", () => setHoveredFragment(frag.id));
    seg.addEventListener("mouseleave", () => clearHoveredFragment());

    tokenBar.appendChild(seg);
  }
}

/**
 * @param {import('../src/types').ContextFragment[]} fragments
 */
function renderFragments(fragments) {
  if (fragments.length === 0) {
    fragmentsEl.innerHTML = "";
    return;
  }

  fragmentsEl.innerHTML = "";
  for (const frag of fragments) {
    const chip = document.createElement("div");
    chip.className = `chip chip-${frag.chipKind}`;
    if (frag.readonly) chip.classList.add("readonly");
    chip.title = frag.shortDescription;
    chip.dataset.id = frag.id;

    chip.addEventListener("mouseenter", () => setHoveredFragment(frag.id));
    chip.addEventListener("mouseleave", () => clearHoveredFragment());

    if (frag.pinned) {
      const pin = document.createElement("span");
      pin.className = "chip-pin";
      pin.textContent = "\u25C6";
      chip.appendChild(pin);
    }

    const text = document.createElement("span");
    text.className = "chip-text";
    text.textContent = frag.shortDescription;
    chip.appendChild(text);

    const close = document.createElement("span");
    close.className = "chip-close";
    close.textContent = "\u00D7";
    close.addEventListener("click", (e) => {
      e.stopPropagation();
      _vscode.postMessage({ type: "drop", fragmentIds: [frag.id] });
    });
    chip.appendChild(close);

    chip.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      showFragmentContextMenu(e, frag);
    });

    fragmentsEl.appendChild(chip);
  }
}

// ── Fragment Context Menu ────────────────────────────

/**
 * @param {MouseEvent} e
 * @param {import('../src/types').ContextFragment} frag
 */
function showFragmentContextMenu(e, frag) {
  const items = [];

  items.push({
    label: frag.pinned ? "Unpin" : "Pin",
    action: () =>
      _vscode.postMessage({
        type: "pin",
        fragmentId: frag.id,
        pinned: !frag.pinned,
      }),
  });

  if (frag.editable) {
    items.push({
      label: frag.readonly ? "Make Editable" : "Make Read-only",
      action: () =>
        _vscode.postMessage({
          type: "readonly",
          fragmentId: frag.id,
          readonly: !frag.readonly,
        }),
    });
  }

  if (frag.chipKind === "HISTORY") {
    items.push({ separator: true });
    items.push({
      label: "Compress History",
      action: () => _vscode.postMessage({ type: "compressHistory" }),
    });
    items.push({
      label: "Clear History",
      action: () => _vscode.postMessage({ type: "clearHistory" }),
    });
  }

  items.push({ separator: true });

  items.push({
    label: "Copy",
    action: () => navigator.clipboard.writeText(frag.shortDescription),
  });

  items.push({
    label: "Drop",
    action: () =>
      _vscode.postMessage({ type: "drop", fragmentIds: [frag.id] }),
  });

  if (currentFragments.length > 1) {
    items.push({
      label: "Drop Others",
      action: () =>
        _vscode.postMessage({ type: "dropOthers", keepId: frag.id }),
    });
  }

  showMenuAt(contextMenu, e, items);
}

// ── Task List ────────────────────────────────────────

/**
 * @param {{ bigPicture: string | null, tasks: { id: string, title: string, text: string, done: boolean }[] } | null} data
 */
export function renderTaskList(data) {
  if (!data || !data.tasks || data.tasks.length === 0) {
    tasklistEl.innerHTML = "";
    return;
  }

  tasklistEl.innerHTML = "";

  for (const task of [...data.tasks].reverse()) {
    const row = document.createElement("div");
    row.className = "task-item" + (task.done ? " done" : "");

    const check = document.createElement("span");
    check.className = "task-check " + (task.done ? "done" : "pending");
    check.textContent = task.done ? "\u2713" : "\u25CB";

    const title = document.createElement("span");
    title.className = "task-title";
    title.textContent = task.title;
    title.title = task.text || task.title;

    row.appendChild(check);
    row.appendChild(title);
    tasklistEl.appendChild(row);
  }
}
