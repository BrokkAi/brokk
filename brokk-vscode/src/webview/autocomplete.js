// @ts-check

/**
 * @-mention autocomplete for the instructions textarea.
 *
 * Detects `@query` patterns in #prompt-input and shows a dropdown
 * with file/class/function completions from the server.
 *
 * @param {ReturnType<typeof acquireVsCodeApi>} vscode
 */
export function initAutocomplete(vscode) {
  const textarea = /** @type {HTMLTextAreaElement} */ (document.getElementById("prompt-input"));
  const dropdown = /** @type {HTMLDivElement} */ (document.getElementById("autocomplete-dropdown"));

  let selectedIndex = -1;
  let currentItems = [];
  let atStartPos = -1;
  let debounceTimer = null;
  let visible = false;

  // ── Trigger Detection ─────────────────────────────────

  textarea.addEventListener("input", () => {
    const pos = textarea.selectionStart;
    const text = textarea.value;

    // Scan backwards from cursor to find `@`
    const atInfo = findAtTrigger(text, pos);
    if (!atInfo) {
      hide();
      return;
    }

    atStartPos = atInfo.atPos;
    const query = atInfo.query;

    if (query.length < 2) {
      // Show placeholder hint when @ is typed with no/short query
      showPlaceholder();
      return;
    }

    // Debounce the request
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      vscode.postMessage({ type: "autocomplete", query });
    }, 150);
  });

  // ── Keyboard Navigation ───────────────────────────────

  textarea.addEventListener("keydown", (e) => {
    if (!visible) return;

    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        selectedIndex = Math.min(selectedIndex + 1, currentItems.length - 1);
        renderSelection();
        break;

      case "ArrowUp":
        e.preventDefault();
        selectedIndex = Math.max(selectedIndex - 1, 0);
        renderSelection();
        break;

      case "Enter":
      case "Tab":
        if (selectedIndex >= 0 && selectedIndex < currentItems.length) {
          e.preventDefault();
          selectItem(currentItems[selectedIndex]);
        }
        break;

      case "Escape":
        e.preventDefault();
        hide();
        break;
    }
  });

  // ── Dismiss on outside click ──────────────────────────

  document.addEventListener("click", (e) => {
    if (visible && !dropdown.contains(/** @type {Node} */ (e.target)) && e.target !== textarea) {
      hide();
    }
  });

  // ── Public: receive results from provider ─────────────

  /**
   * @param {{ completions: Array<{ type: string, name: string, detail: string }> }} data
   */
  function handleResults(data) {
    currentItems = data.completions || [];
    if (currentItems.length === 0) {
      hide();
      return;
    }
    selectedIndex = 0;
    renderDropdown();
    show();
  }

  // ── Placeholder ───────────────────────────────────────

  function showPlaceholder() {
    currentItems = [];
    selectedIndex = -1;
    dropdown.innerHTML = "";

    const hint = document.createElement("div");
    hint.className = "autocomplete-hint";
    hint.innerHTML =
      '<span class="autocomplete-hint-title">Search for files or symbols</span>' +
      '<span class="autocomplete-hint-example">Type a class name, method, or file path</span>';
    dropdown.appendChild(hint);

    positionDropdown();
    dropdown.classList.remove("hidden");
    visible = true;
  }

  // ── Rendering ─────────────────────────────────────────

  function renderDropdown() {
    dropdown.innerHTML = "";
    currentItems.forEach((item, i) => {
      const el = document.createElement("div");
      el.className = "autocomplete-item" + (i === selectedIndex ? " selected" : "");

      const icon = document.createElement("span");
      icon.className = "autocomplete-icon";
      icon.textContent = getIcon(item.type);

      const name = document.createElement("span");
      name.className = "autocomplete-name";
      name.textContent = item.name;

      const detail = document.createElement("span");
      detail.className = "autocomplete-detail";
      detail.textContent = item.detail;

      el.appendChild(icon);
      el.appendChild(name);
      el.appendChild(detail);

      el.addEventListener("mousedown", (e) => {
        e.preventDefault(); // prevent textarea blur
        selectItem(item);
      });

      el.addEventListener("mouseenter", () => {
        selectedIndex = i;
        renderSelection();
      });

      dropdown.appendChild(el);
    });
  }

  function renderSelection() {
    const items = dropdown.querySelectorAll(".autocomplete-item");
    items.forEach((el, i) => {
      el.classList.toggle("selected", i === selectedIndex);
    });
    // Scroll selected item into view
    if (selectedIndex >= 0 && items[selectedIndex]) {
      items[selectedIndex].scrollIntoView({ block: "nearest" });
    }
  }

  /**
   * @param {string} type
   * @returns {string}
   */
  function getIcon(type) {
    switch (type) {
      case "file": return "\u{1F4C4}";
      case "class": return "C";
      case "function": return "f";
      case "field": return "F";
      case "module": return "M";
      default: return "\u2022";
    }
  }

  // ── Selection ─────────────────────────────────────────

  /**
   * @param {{ type: string, name: string, detail: string }} item
   */
  function selectItem(item) {
    const text = textarea.value;
    const cursorPos = textarea.selectionStart;

    // Replace @query with `detail` (full path or fqName) for unambiguous references
    const before = text.substring(0, atStartPos);
    const after = text.substring(cursorPos);
    const insertion = "`" + item.detail + "` ";

    textarea.value = before + insertion + after;

    // Position cursor after insertion
    const newPos = atStartPos + insertion.length;
    textarea.selectionStart = newPos;
    textarea.selectionEnd = newPos;
    textarea.focus();

    hide();
  }

  // ── Positioning & Visibility ──────────────────────────

  function show() {
    positionDropdown();
    dropdown.classList.remove("hidden");
    visible = true;
  }

  function hide() {
    dropdown.classList.add("hidden");
    visible = false;
    selectedIndex = -1;
    currentItems = [];
    atStartPos = -1;
    if (debounceTimer) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
  }

  function positionDropdown() {
    // Position above the textarea
    const inputArea = document.getElementById("input-area");
    if (!inputArea) return;
    const rect = textarea.getBoundingClientRect();
    const parentRect = inputArea.getBoundingClientRect();
    dropdown.style.bottom = (parentRect.bottom - rect.top + 4) + "px";
    dropdown.style.left = "8px";
    dropdown.style.right = "8px";
  }

  // ── Helpers ───────────────────────────────────────────

  /**
   * Scan backwards from cursor to find an `@` trigger.
   * Returns null if no valid trigger is found.
   * @param {string} text
   * @param {number} cursorPos
   * @returns {{ atPos: number, query: string } | null}
   */
  function findAtTrigger(text, cursorPos) {
    // Scan back from cursor
    for (let i = cursorPos - 1; i >= 0; i--) {
      const ch = text[i];
      if (ch === "@") {
        // Check that @ is at start of line or preceded by whitespace
        if (i === 0 || /\s/.test(text[i - 1])) {
          return {
            atPos: i,
            query: text.substring(i + 1, cursorPos),
          };
        }
        return null;
      }
      // Stop at whitespace or newlines (but allow / and \ for file paths)
      if (ch === "\n" || ch === "\r") {
        return null;
      }
    }
    return null;
  }

  return { handleResults };
}
