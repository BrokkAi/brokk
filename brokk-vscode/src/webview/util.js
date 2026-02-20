// @ts-check

/**
 * Escape HTML special characters.
 * @param {string} str
 * @returns {string}
 */
export function escapeHtml(str) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/**
 * Show a context menu at the given mouse position.
 * @param {HTMLElement} menuEl  The context-menu container element
 * @param {MouseEvent} e        The triggering mouse event (for position)
 * @param {{ label?: string, action?: () => void, separator?: boolean }[]} items
 */
export function showMenuAt(menuEl, e, items) {
  menuEl.innerHTML = "";
  menuEl.classList.remove("hidden");

  for (const item of items) {
    if (item.separator) {
      const sep = document.createElement("div");
      sep.className = "context-menu-separator";
      menuEl.appendChild(sep);
    } else {
      const el = document.createElement("div");
      el.className = "context-menu-item";
      el.textContent = item.label;
      el.addEventListener("click", () => {
        menuEl.classList.add("hidden");
        item.action();
      });
      menuEl.appendChild(el);
    }
  }

  menuEl.style.left = e.clientX + "px";
  menuEl.style.top = e.clientY + "px";

  requestAnimationFrame(() => {
    const rect = menuEl.getBoundingClientRect();
    if (rect.right > window.innerWidth) {
      menuEl.style.left = window.innerWidth - rect.width - 4 + "px";
    }
    if (rect.bottom > window.innerHeight) {
      menuEl.style.top = window.innerHeight - rect.height - 4 + "px";
    }
  });
}
