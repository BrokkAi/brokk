// @ts-check

/**
 * Initialize the settings overlay and return message handlers.
 * @param {ReturnType<typeof acquireVsCodeApi>} vscode
 * @returns {{ onSettingsLoaded: (msg: any) => void, onSettingsSaved: (msg: any) => void, onBalanceResult: (msg: any) => void, onSettingsError: (msg: any) => void }}
 */
export function initSettings(vscode) {
  const overlay = document.getElementById("settings-overlay");
  const closeBtn = document.getElementById("settings-close-btn");
  const settingsBtn = document.getElementById("settings-btn");
  const apiKeyInput = /** @type {HTMLInputElement} */ (document.getElementById("settings-api-key"));
  const toggleKeyBtn = document.getElementById("settings-toggle-key");
  const balanceEl = document.getElementById("settings-balance");
  const saveBtn = document.getElementById("settings-save-btn");
  const messageEl = document.getElementById("settings-message");
  const hintEl = document.getElementById("settings-key-hint");

  settingsBtn.addEventListener("click", () => {
    overlay.classList.remove("hidden");
    messageEl.classList.add("hidden");
    vscode.postMessage({ type: "loadSettings" });
  });

  closeBtn.addEventListener("click", () => {
    overlay.classList.add("hidden");
  });

  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) {
      overlay.classList.add("hidden");
    }
  });

  toggleKeyBtn.addEventListener("click", () => {
    if (apiKeyInput.type === "password") {
      apiKeyInput.type = "text";
    } else {
      apiKeyInput.type = "password";
    }
  });

  saveBtn.addEventListener("click", () => {
    saveBtn.disabled = true;
    saveBtn.textContent = "Saving...";
    messageEl.classList.add("hidden");
    vscode.postMessage({ type: "saveApiKey", apiKey: apiKeyInput.value });
  });

  function showSettingsMessage(text, isError) {
    messageEl.textContent = text;
    messageEl.className = "settings-message " + (isError ? "error" : "success");
  }

  return {
    onSettingsLoaded(msg) {
      apiKeyInput.value = msg.apiKey || "";
      hintEl.style.display = msg.apiKey ? "none" : "";
      balanceEl.textContent = "--";
      if (msg.apiKey) {
        vscode.postMessage({ type: "fetchBalance" });
      }
    },

    onSettingsSaved(msg) {
      saveBtn.disabled = false;
      saveBtn.textContent = "Save";
      if (msg.balance != null) {
        balanceEl.textContent = "$" + msg.balance.toFixed(2);
        hintEl.style.display = "none";
      } else {
        balanceEl.textContent = "--";
        hintEl.style.display = "";
      }
      showSettingsMessage("Saved", false);
    },

    onBalanceResult(msg) {
      if (msg.balance != null) {
        balanceEl.textContent = "$" + msg.balance.toFixed(2);
      } else {
        balanceEl.textContent = "--";
      }
    },

    onSettingsError(msg) {
      saveBtn.disabled = false;
      saveBtn.textContent = "Save";
      showSettingsMessage(msg.message || "Error", true);
    },
  };
}
