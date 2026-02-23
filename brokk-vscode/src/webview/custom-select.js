// @ts-check
import { escapeHtml } from "./util.js";

/** @type {ReturnType<typeof initCustomSelect>[]} */
const allCustomSelects = [];

const REASONING_LEVELS = ["DEFAULT", "DISABLE", "LOW", "MEDIUM", "HIGH"];

// ── Global mouse tracking ────────────────────────────

let lastMouse = { x: 0, y: 0 };
document.addEventListener("mousemove", (e) => { lastMouse.x = e.clientX; lastMouse.y = e.clientY; }, true);

// ── Active submenu state (singleton) ─────────────────

let activeSubmenu = {
  /** @type {HTMLElement | null} */ el: null,
  /** @type {HTMLElement | null} */ sourceOpt: null,
  /** @type {number} */ hideTimer: 0,
  /** @type {{ x: number, y: number } | null} */ anchorMouse: null,
  /** @type {boolean} */ placedRight: true,
};

function _destroySubmenu() {
  if (activeSubmenu.hideTimer) { clearTimeout(activeSubmenu.hideTimer); activeSubmenu.hideTimer = 0; }
  if (activeSubmenu.sourceOpt) activeSubmenu.sourceOpt.classList.remove("submenu-active");
  if (activeSubmenu.el) { activeSubmenu.el.remove(); activeSubmenu.el = null; }
  activeSubmenu.sourceOpt = null;
  activeSubmenu.anchorMouse = null;
}

function _scheduleSubmenuHide(ms) {
  if (activeSubmenu.hideTimer) clearTimeout(activeSubmenu.hideTimer);
  activeSubmenu.hideTimer = setTimeout(() => _destroySubmenu(), ms);
}

/**
 * Check if point (px, py) is inside the triangle formed by p1, p2, p3
 * using the sign-of-cross-product method.
 */
function _pointInTriangle(px, py, p1, p2, p3) {
  const d1 = (px - p2.x) * (p1.y - p2.y) - (p1.x - p2.x) * (py - p2.y);
  const d2 = (px - p3.x) * (p2.y - p3.y) - (p2.x - p3.x) * (py - p3.y);
  const d3 = (px - p1.x) * (p3.y - p1.y) - (p3.x - p1.x) * (py - p1.y);
  const hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
  const hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
  return !(hasNeg && hasPos);
}

/**
 * Check if the mouse is currently within the safe triangle between the
 * anchor point and the submenu corners.
 */
function _isInSafeTriangle(mx, my) {
  if (!activeSubmenu.el || !activeSubmenu.anchorMouse) return false;
  const subRect = activeSubmenu.el.getBoundingClientRect();
  const anchor = activeSubmenu.anchorMouse;

  let p2, p3;
  if (activeSubmenu.placedRight) {
    p2 = { x: subRect.left, y: subRect.top };
    p3 = { x: subRect.left, y: subRect.bottom };
  } else {
    p2 = { x: subRect.right, y: subRect.top };
    p3 = { x: subRect.right, y: subRect.bottom };
  }
  return _pointInTriangle(mx, my, anchor, p2, p3);
}

// ── initCustomSelect ─────────────────────────────────

/**
 * Initialize a custom select dropdown.
 * @param {string} containerId
 */
export function initCustomSelect(containerId) {
  const container = document.getElementById(containerId);
  const trigger = container.querySelector(".custom-select-trigger");
  const dropdown = container.querySelector(".custom-select-dropdown");
  const valueEl = container.querySelector(".custom-select-value");

  let currentValue = "";
  /** @type {((value: string, extra?: any) => void) | null} */
  let changeCallback = null;

  function bindOptions() {
    const opts = container.querySelectorAll(".custom-select-option");
    const selected = [...opts].find((o) => o.classList.contains("selected"));
    if (selected) currentValue = selected.dataset.value;

    for (const opt of opts) {
      if (opt._bound) continue;
      opt._bound = true;
      opt.addEventListener("click", (e) => {
        e.stopPropagation();
        if (e.target.closest(".reasoning-submenu")) return;
        currentValue = opt.dataset.value;
        const label = opt.querySelector(".option-label");
        valueEl.textContent = label ? label.textContent : opt.textContent.trim();
        opts.forEach((o) => o.classList.remove("selected"));
        opt.classList.add("selected");
        dropdown.classList.add("hidden");
        _destroySubmenu();
        container.dispatchEvent(new Event("change"));
        if (changeCallback) changeCallback(currentValue, opt.dataset);
      });
    }
  }

  bindOptions();

  trigger.addEventListener("click", (e) => {
    e.stopPropagation();
    const wasOpen = !dropdown.classList.contains("hidden");
    closeAllDropdowns();
    if (!wasOpen) dropdown.classList.remove("hidden");
  });

  const sel = {
    get value() { return currentValue; },
    set value(v) {
      currentValue = v;
      const opt = container.querySelector(`.custom-select-option[data-value="${CSS.escape(v)}"]`);
      if (opt) {
        const label = opt.querySelector(".option-label");
        valueEl.textContent = label ? label.textContent : opt.textContent.trim();
        container.querySelectorAll(".custom-select-option").forEach((o) => o.classList.remove("selected"));
        opt.classList.add("selected");
      }
    },
    container,
    dropdown,
    valueEl,
    close() { dropdown.classList.add("hidden"); _destroySubmenu(); },
    addEventListener(type, fn) { container.addEventListener(type, fn); },
    rebind() { bindOptions(); },
    onChange(fn) { changeCallback = fn; },
    _reasoning: "DEFAULT",
    get reasoning() { return this._reasoning; },
    set reasoning(v) { this._reasoning = v; },
  };

  allCustomSelects.push(sel);
  return sel;
}

export function closeAllDropdowns() {
  _destroySubmenu();
  for (const sel of allCustomSelects) sel.dropdown.classList.add("hidden");
}

// ── Types ────────────────────────────────────────────

/**
 * @typedef {{ alias: string, modelName: string, reasoning: string, tier: string }} FavoriteInfo
 * @typedef {{ name: string, location: string, supportsReasoningEffort: boolean, supportsReasoningDisable: boolean }} ModelInfoObj
 */

/**
 * Populate model dropdowns.
 * @param {ReturnType<typeof initCustomSelect>} plannerSelect
 * @param {ReturnType<typeof initCustomSelect>} codeSelect
 * @param {(ModelInfoObj | string)[]} models
 * @param {FavoriteInfo[]} favorites
 */
export function populateModelSelects(plannerSelect, codeSelect, models, favorites) {
  const modelInfos = models.map((m) => typeof m === "string"
    ? { name: m, location: "", supportsReasoningEffort: false, supportsReasoningDisable: false }
    : m
  );
  _populateModelDropdown(plannerSelect, modelInfos, favorites, true);
  _populateModelDropdown(codeSelect, modelInfos, favorites, false);
}

/**
 * @param {ReturnType<typeof initCustomSelect>} select
 * @param {ModelInfoObj[]} modelInfos
 * @param {FavoriteInfo[]} favorites
 * @param {boolean} isPrimary
 */
function _populateModelDropdown(select, modelInfos, favorites, isPrimary) {
  const dropdown = select.dropdown;
  dropdown.innerHTML = "";

  if (!isPrimary) {
    const defaultCode = "gemini-3-flash-preview";
    const defaultOpt = document.createElement("div");
    defaultOpt.className = "custom-select-option selected";
    defaultOpt.dataset.value = defaultCode;
    defaultOpt.innerHTML = `<span class="option-label">${defaultCode}</span>`;
    dropdown.appendChild(defaultOpt);
    select.valueEl.textContent = defaultCode;
  }

  // Favorites
  if (favorites.length > 0) {
    for (const fav of favorites) {
      const opt = document.createElement("div");
      opt.className = "custom-select-option favorite-option";
      if (isPrimary && dropdown.children.length === 0) opt.classList.add("selected");
      opt.dataset.value = fav.modelName;
      opt.dataset.reasoning = fav.reasoning;
      opt.dataset.tier = fav.tier;
      opt.dataset.isFavorite = "true";

      const reasoningLabel = fav.reasoning !== "DEFAULT" && fav.reasoning !== "DISABLE"
        ? ` (${fav.reasoning.toLowerCase()} reasoning)`
        : fav.reasoning === "DISABLE" ? " (no reasoning)" : "";

      opt.innerHTML =
        `<span class="option-label">${escapeHtml(fav.alias)}</span>` +
        `<span class="option-desc">${escapeHtml(fav.modelName)}${reasoningLabel}</span>`;
      dropdown.appendChild(opt);
    }

    const sep = document.createElement("div");
    sep.className = "custom-select-separator";
    dropdown.appendChild(sep);
  }

  // Show-all toggle
  const toggleContainer = document.createElement("div");
  toggleContainer.className = "show-all-toggle";
  const toggleLabel = document.createElement("label");
  toggleLabel.className = "show-all-label";
  const toggleCheckbox = document.createElement("input");
  toggleCheckbox.type = "checkbox";
  toggleCheckbox.className = "show-all-checkbox";
  toggleLabel.appendChild(toggleCheckbox);
  toggleLabel.appendChild(document.createTextNode(" Show all models"));
  toggleContainer.appendChild(toggleLabel);
  dropdown.appendChild(toggleContainer);

  // All models container
  const allModelsContainer = document.createElement("div");
  allModelsContainer.className = "all-models-container hidden";

  for (const info of modelInfos) {
    const opt = document.createElement("div");
    opt.className = "custom-select-option";
    const hasReasoning = info.supportsReasoningEffort || info.supportsReasoningDisable;
    if (hasReasoning) opt.classList.add("has-submenu");
    opt.dataset.value = info.name;
    opt.innerHTML = `<span class="option-label">${escapeHtml(info.name)}</span>`;

    if (hasReasoning) {
      _attachReasoningHover(opt, select, info, dropdown);
    }

    allModelsContainer.appendChild(opt);
  }
  dropdown.appendChild(allModelsContainer);

  toggleCheckbox.addEventListener("click", (e) => e.stopPropagation());
  toggleCheckbox.addEventListener("change", (e) => {
    e.stopPropagation();
    allModelsContainer.classList.toggle("hidden", !toggleCheckbox.checked);
  });
  toggleLabel.addEventListener("click", (e) => e.stopPropagation());

  // Initial value
  if (isPrimary) {
    if (favorites.length > 0) {
      select.valueEl.textContent = favorites[0].alias;
      select.reasoning = favorites[0].reasoning;
    } else {
      const defaultPlanner = "claude-opus-4-6";
      const match = allModelsContainer.querySelector(`.custom-select-option[data-value="${defaultPlanner}"]`);
      if (match) {
        match.classList.add("selected");
        toggleCheckbox.checked = true;
        allModelsContainer.classList.remove("hidden");
        select.valueEl.textContent = defaultPlanner;
      } else if (modelInfos.length > 0) {
        toggleCheckbox.checked = true;
        allModelsContainer.classList.remove("hidden");
        const firstAll = allModelsContainer.querySelector(".custom-select-option");
        if (firstAll) firstAll.classList.add("selected");
        select.valueEl.textContent = modelInfos[0].name;
      }
      select.reasoning = "DEFAULT";
    }
  } else {
    select.reasoning = "DEFAULT";
  }

  select.rebind();
}

// ── Reasoning submenu with point-in-triangle safe zone ──

/**
 * @param {HTMLElement} opt
 * @param {ReturnType<typeof initCustomSelect>} select
 * @param {ModelInfoObj} info
 * @param {HTMLElement} dropdown
 */
function _attachReasoningHover(opt, select, info, dropdown) {
  let intentTimer = null;

  opt.addEventListener("mouseenter", () => {
    // Already open for this opt — just cancel hide
    if (activeSubmenu.sourceOpt === opt) {
      if (activeSubmenu.hideTimer) { clearTimeout(activeSubmenu.hideTimer); activeSubmenu.hideTimer = 0; }
      return;
    }

    // If a different submenu is open, check safe-triangle before switching
    if (activeSubmenu.el && activeSubmenu.sourceOpt !== opt) {
      if (_isInSafeTriangle(lastMouse.x, lastMouse.y)) {
        // Mouse is in the safe zone heading toward the open submenu — don't switch
        return;
      }
    }

    if (intentTimer) clearTimeout(intentTimer);
    intentTimer = setTimeout(() => {
      _showReasoningSubmenu(opt, select, info, dropdown);
    }, 60);
  });

  opt.addEventListener("mouseleave", () => {
    if (intentTimer) { clearTimeout(intentTimer); intentTimer = null; }
    if (activeSubmenu.sourceOpt === opt) {
      _scheduleSubmenuHide(120);
    }
  });

  // Also check triangle on mousemove within the dropdown to prevent
  // premature submenu switching while the user moves diagonally.
  opt.addEventListener("mousemove", () => {
    if (activeSubmenu.sourceOpt && activeSubmenu.sourceOpt !== opt && activeSubmenu.el) {
      // Update: if mouse is in safe triangle, cancel any pending open for this opt
      if (_isInSafeTriangle(lastMouse.x, lastMouse.y)) {
        if (intentTimer) { clearTimeout(intentTimer); intentTimer = null; }
      }
    }
  });
}

/**
 * @param {HTMLElement} opt
 * @param {ReturnType<typeof initCustomSelect>} select
 * @param {ModelInfoObj} info
 * @param {HTMLElement} dropdown
 */
function _showReasoningSubmenu(opt, select, info, dropdown) {
  _destroySubmenu();

  const optRect = opt.getBoundingClientRect();
  const dropRect = dropdown.getBoundingClientRect();

  const submenu = document.createElement("div");
  submenu.className = "reasoning-submenu";

  const title = document.createElement("div");
  title.className = "reasoning-submenu-title";
  title.textContent = "Reasoning";
  submenu.appendChild(title);

  const levels = info.supportsReasoningEffort
    ? REASONING_LEVELS
    : info.supportsReasoningDisable ? ["DEFAULT", "DISABLE"] : ["DEFAULT"];

  for (const level of levels) {
    const item = document.createElement("div");
    item.className = "reasoning-submenu-option";
    if (level === "DEFAULT") item.classList.add("selected");
    item.textContent = level.charAt(0) + level.slice(1).toLowerCase();
    item.dataset.reasoning = level;

    item.addEventListener("click", (e) => {
      e.stopPropagation();
      select.reasoning = level;
      const allOpts = dropdown.querySelectorAll(".custom-select-option");
      allOpts.forEach((o) => o.classList.remove("selected"));
      opt.classList.add("selected");

      const label = opt.querySelector(".option-label");
      const displayText = label ? label.textContent : opt.textContent.trim();
      select.valueEl.textContent = level !== "DEFAULT"
        ? `${displayText} (${level.toLowerCase()})`
        : displayText;

      select._reasoning = level;
      const prevReasoning = select._reasoning;
      select.value = opt.dataset.value;
      select._reasoning = prevReasoning;

      dropdown.classList.add("hidden");
      _destroySubmenu();
      select.container.dispatchEvent(new Event("change"));
    });
    submenu.appendChild(item);
  }

  document.body.appendChild(submenu);

  // Position submenu
  const submenuRect = submenu.getBoundingClientRect();
  const gap = 2;

  let left = dropRect.right + gap;
  let placedRight = true;
  if (left + submenuRect.width > window.innerWidth) {
    left = dropRect.left - submenuRect.width - gap;
    placedRight = false;
  }
  if (left < 0) left = 0;

  let top = optRect.top;
  if (top + submenuRect.height > window.innerHeight) {
    top = window.innerHeight - submenuRect.height - 4;
  }
  if (top < 0) top = 0;

  submenu.style.left = left + "px";
  submenu.style.top = top + "px";

  // Highlight source option
  opt.classList.add("submenu-active");

  // Store state
  activeSubmenu.el = submenu;
  activeSubmenu.sourceOpt = opt;
  activeSubmenu.anchorMouse = { x: lastMouse.x, y: lastMouse.y };
  activeSubmenu.placedRight = placedRight;

  submenu.addEventListener("mouseenter", () => {
    if (activeSubmenu.hideTimer) { clearTimeout(activeSubmenu.hideTimer); activeSubmenu.hideTimer = 0; }
  });
  submenu.addEventListener("mouseleave", () => {
    // Clear the anchor so the safe-triangle check won't block new options
    activeSubmenu.anchorMouse = null;
    _scheduleSubmenuHide(100);
  });
}
