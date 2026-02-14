// @ts-check
import { escapeHtml } from "./util.js";

/** @type {ReturnType<typeof initCustomSelect>[]} */
const allCustomSelects = [];

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

  function bindOptions() {
    const opts = container.querySelectorAll(".custom-select-option");
    const selected = [...opts].find((o) => o.classList.contains("selected"));
    if (selected) currentValue = selected.dataset.value;

    for (const opt of opts) {
      if (opt._bound) continue;
      opt._bound = true;
      opt.addEventListener("click", (e) => {
        e.stopPropagation();
        currentValue = opt.dataset.value;
        const label = opt.querySelector(".option-label");
        valueEl.textContent = label ? label.textContent : opt.textContent.trim();
        opts.forEach((o) => o.classList.remove("selected"));
        opt.classList.add("selected");
        dropdown.classList.add("hidden");
        container.dispatchEvent(new Event("change"));
      });
    }
  }

  bindOptions();

  trigger.addEventListener("click", (e) => {
    e.stopPropagation();
    const wasOpen = !dropdown.classList.contains("hidden");
    closeAllDropdowns();
    if (!wasOpen) {
      dropdown.classList.remove("hidden");
    }
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
    close() { dropdown.classList.add("hidden"); },
    addEventListener(type, fn) { container.addEventListener(type, fn); },
    /** Re-scan options after dynamically adding them */
    rebind() { bindOptions(); },
  };

  allCustomSelects.push(sel);
  return sel;
}

export function closeAllDropdowns() {
  for (const sel of allCustomSelects) {
    sel.close();
  }
}

/**
 * Populate model dropdowns from server-provided model list.
 * @param {ReturnType<typeof initCustomSelect>} plannerSelect
 * @param {ReturnType<typeof initCustomSelect>} codeSelect
 * @param {string[]} models  Sorted list of available model names
 */
export function populateModelSelects(plannerSelect, codeSelect, models) {
  // Populate planner dropdown
  const plannerDropdown = plannerSelect.dropdown;
  plannerDropdown.innerHTML = "";
  for (const model of models) {
    const opt = document.createElement("div");
    opt.className = "custom-select-option";
    opt.dataset.value = model;
    opt.innerHTML = `<span class="option-label">${escapeHtml(model)}</span>`;
    plannerDropdown.appendChild(opt);
  }
  const firstOpt = plannerDropdown.querySelector(".custom-select-option");
  if (firstOpt) {
    firstOpt.classList.add("selected");
    plannerSelect.valueEl.textContent = models[0];
  }
  plannerSelect.rebind();

  // Populate code dropdown (keep "(same as primary)" as first option)
  const codeDropdown = codeSelect.dropdown;
  codeDropdown.innerHTML = "";
  const sameOpt = document.createElement("div");
  sameOpt.className = "custom-select-option selected";
  sameOpt.dataset.value = "";
  sameOpt.innerHTML = `<span class="option-label">(same as primary)</span>`;
  codeDropdown.appendChild(sameOpt);
  for (const model of models) {
    const opt = document.createElement("div");
    opt.className = "custom-select-option";
    opt.dataset.value = model;
    opt.innerHTML = `<span class="option-label">${escapeHtml(model)}</span>`;
    codeDropdown.appendChild(opt);
  }
  codeSelect.rebind();
}
