// @ts-check
import { showMenuAt } from "./util.js";

// ── DOM Elements ─────────────────────────────────────

const activityPanel = document.getElementById("activity-panel");
const activityHeader = document.getElementById("activity-header");
const activityToggle = document.getElementById("activity-toggle");
const activitySessionName = document.getElementById("activity-session-name");
const switchSessionBtn = /** @type {HTMLButtonElement} */ (document.getElementById("switch-session-btn"));
const activityLatest = document.getElementById("activity-latest");
const activityGroupsEl = document.getElementById("activity-groups");
const undoBtn = /** @type {HTMLButtonElement} */ (document.getElementById("undo-btn"));
const redoBtn = /** @type {HTMLButtonElement} */ (document.getElementById("redo-btn"));
const contextMenu = document.getElementById("context-menu");

// ── State ────────────────────────────────────────────

/** @type {Map<string, boolean>} */
const expandedGroups = new Map();

/** @type {ReturnType<typeof acquireVsCodeApi> | null} */
let _vscode = null;

// ── Init ─────────────────────────────────────────────

/**
 * Initialize activity panel event listeners.
 * @param {ReturnType<typeof acquireVsCodeApi>} vscodeApi
 */
export function initActivity(vscodeApi) {
  _vscode = vscodeApi;

  activityHeader.addEventListener("click", (e) => {
    if (e.target === undoBtn || e.target === redoBtn) return;
    const isCollapsed = activityPanel.classList.contains("collapsed");
    activityPanel.classList.toggle("collapsed", !isCollapsed);
    activityPanel.classList.toggle("expanded", isCollapsed);
    activityToggle.innerHTML = isCollapsed ? "&#9660;" : "&#9654;";
  });

  switchSessionBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    _vscode.postMessage({ type: "switchSession" });
  });

  undoBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    _vscode.postMessage({ type: "undoStep" });
  });

  redoBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    _vscode.postMessage({ type: "redoStep" });
  });
}

// ── Rendering ────────────────────────────────────────

/**
 * @param {{ id: string, name: string }} session
 * @param {{ groups: { key: string, label: string, showHeader: boolean, isLastGroup: boolean, entries: { contextId: string, action: string, taskType?: string, isAiResult: boolean }[] }[], hasUndo: boolean, hasRedo: boolean }} activity
 */
export function renderActivity(session, activity) {
  console.log("[Brokk] renderActivity called", session.name, activity.groups.length, "groups");
  activitySessionName.textContent = session.name || "Session";

  undoBtn.disabled = !activity.hasUndo;
  redoBtn.disabled = !activity.hasRedo;

  const allEntries = activity.groups.flatMap((g) => g.entries);
  const totalEntries = allEntries.length;
  const latest = totalEntries > 0 ? allEntries[totalEntries - 1] : null;
  activityLatest.textContent = latest ? latest.action : "";
  if (totalEntries > 0) {
    requestAnimationFrame(() => {
      if (activityLatest.scrollWidth > activityLatest.clientWidth) {
        activityLatest.textContent = `(${totalEntries})`;
      }
    });
  }

  activityGroupsEl.innerHTML = "";

  for (const group of activity.groups) {
    const groupEl = document.createElement("div");
    groupEl.className = "activity-group";

    if (group.showHeader && group.label) {
      const headerEl = document.createElement("div");
      headerEl.className = "activity-group-header";

      const isExpanded = expandedGroups.get(group.key) ?? group.isLastGroup;

      const toggleEl = document.createElement("span");
      toggleEl.className = "activity-group-toggle";
      toggleEl.innerHTML = isExpanded ? "&#9660;" : "&#9654;";

      const labelEl = document.createElement("span");
      labelEl.className = "activity-group-label";
      labelEl.textContent = group.label;
      labelEl.title = group.label;

      const countEl = document.createElement("span");
      countEl.className = "activity-group-count";
      countEl.textContent = `(${group.entries.length})`;

      headerEl.appendChild(toggleEl);
      headerEl.appendChild(labelEl);
      headerEl.appendChild(countEl);

      headerEl.addEventListener("click", () => {
        const nowExpanded = !expandedGroups.get(group.key);
        expandedGroups.set(group.key, nowExpanded);
        toggleEl.innerHTML = nowExpanded ? "&#9660;" : "&#9654;";
        entriesEl.style.display = nowExpanded ? "block" : "none";
      });

      groupEl.appendChild(headerEl);

      const entriesEl = document.createElement("div");
      entriesEl.className = "activity-entries";
      entriesEl.style.display = isExpanded ? "block" : "none";

      for (const entry of group.entries) {
        entriesEl.appendChild(createEntryEl(entry));
      }
      groupEl.appendChild(entriesEl);
    } else {
      for (const entry of group.entries) {
        groupEl.appendChild(createEntryEl(entry));
      }
    }

    activityGroupsEl.appendChild(groupEl);
  }
}

// ── Internal Helpers ─────────────────────────────────

/**
 * @param {{ contextId: string, action: string, taskType?: string, isAiResult: boolean }} entry
 * @returns {HTMLElement}
 */
function createEntryEl(entry) {
  const el = document.createElement("div");
  el.className = "activity-entry";
  if (entry.isAiResult) el.classList.add("ai-result");

  if (entry.taskType) {
    const badge = document.createElement("span");
    badge.className = `activity-task-badge badge-${entry.taskType.toUpperCase()}`;
    badge.textContent = entry.taskType;
    el.appendChild(badge);
  }

  const actionEl = document.createElement("span");
  actionEl.className = "activity-action-text";
  actionEl.textContent = entry.action;
  actionEl.title = entry.action;
  el.appendChild(actionEl);

  el.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    showActivityContextMenu(e, entry.contextId);
  });

  return el;
}

/**
 * @param {MouseEvent} e
 * @param {string} contextId
 */
function showActivityContextMenu(e, contextId) {
  const items = [
    {
      label: "Undo to here",
      action: () =>
        _vscode.postMessage({ type: "activityAction", action: "undo", contextId }),
    },
    {
      label: "Copy Context",
      action: () =>
        _vscode.postMessage({ type: "activityAction", action: "copyContext", contextId }),
    },
    {
      label: "Copy Context + History",
      action: () =>
        _vscode.postMessage({ type: "activityAction", action: "copyContextHistory", contextId }),
    },
    { separator: true },
    {
      label: "Show Diff",
      action: () =>
        _vscode.postMessage({ type: "activityAction", action: "showDiff", contextId }),
    },
    { separator: true },
    {
      label: "New Session from Workspace",
      action: () =>
        _vscode.postMessage({ type: "activityAction", action: "newSession", contextId }),
    },
  ];

  showMenuAt(contextMenu, e, items);
}
