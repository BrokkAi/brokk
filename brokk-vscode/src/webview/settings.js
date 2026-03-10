// @ts-check

/**
 * Initialize the settings overlay and return message handlers.
 * @param {ReturnType<typeof acquireVsCodeApi>} vscode
 * @returns {{ onSettingsLoaded: (msg: any) => void, onSettingsSaved: (msg: any) => void, onBalanceResult: (msg: any) => void, onSettingsError: (msg: any) => void, onOpenAiStatusResult: (msg: any) => void, onOpenAiConnectStarted: (msg: any) => void }}
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
  const clearKeyBtn = document.getElementById("settings-clear-key");
  const openAiStatusLabel = document.getElementById("openai-status-label");
  const openAiConnectBtn = document.getElementById("openai-connect-btn");

  /** @type {ReturnType<typeof setInterval> | null} */
  let openAiPollTimer = null;
  /** @type {ReturnType<typeof setTimeout> | null} */
  let openAiPollTimeout = null;

  function updateOpenAiUi(connected, unavailable, error) {
    if (unavailable) {
      openAiStatusLabel.textContent = "Unavailable (executor not connected)";
      openAiStatusLabel.className = "subscription-status unavailable";
      openAiConnectBtn.classList.add("hidden");
    } else if (connected) {
      openAiStatusLabel.textContent = "OpenAI: Connected";
      openAiStatusLabel.className = "subscription-status connected";
      openAiConnectBtn.classList.add("hidden");
      stopOpenAiPolling();
    } else if (error) {
      openAiStatusLabel.textContent = "OpenAI: Error — " + error;
      openAiStatusLabel.className = "subscription-status unavailable";
      openAiConnectBtn.classList.remove("hidden");
      openAiConnectBtn.disabled = false;
      openAiConnectBtn.textContent = "Retry";
    } else {
      openAiStatusLabel.textContent = "OpenAI: Not connected";
      openAiStatusLabel.className = "subscription-status";
      openAiConnectBtn.classList.remove("hidden");
      openAiConnectBtn.disabled = false;
      openAiConnectBtn.textContent = "Connect";
    }
  }

  function startOpenAiPolling() {
    stopOpenAiPolling();
    openAiPollTimer = setInterval(() => {
      vscode.postMessage({ type: "pollOpenAiStatus" });
    }, 2000);
    openAiPollTimeout = setTimeout(() => {
      stopOpenAiPolling();
    }, 120000);
  }

  function stopOpenAiPolling() {
    if (openAiPollTimer) {
      clearInterval(openAiPollTimer);
      openAiPollTimer = null;
    }
    if (openAiPollTimeout) {
      clearTimeout(openAiPollTimeout);
      openAiPollTimeout = null;
    }
  }

  openAiConnectBtn.addEventListener("click", () => {
    openAiConnectBtn.disabled = true;
    openAiConnectBtn.textContent = "Connecting...";
    openAiStatusLabel.textContent = "Opening browser...";
    openAiStatusLabel.className = "subscription-status";
    vscode.postMessage({ type: "connectOpenAi" });
  });

  settingsBtn.addEventListener("click", () => {
    overlay.classList.remove("hidden");
    messageEl.classList.add("hidden");
    vscode.postMessage({ type: "loadSettings" });
  });

  closeBtn.addEventListener("click", () => {
    overlay.classList.add("hidden");
    stopOpenAiPolling();
  });

  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) {
      overlay.classList.add("hidden");
      stopOpenAiPolling();
    }
  });

  toggleKeyBtn.addEventListener("click", () => {
    if (apiKeyInput.type === "password") {
      apiKeyInput.type = "text";
    } else {
      apiKeyInput.type = "password";
    }
  });

  clearKeyBtn.addEventListener("click", () => {
    apiKeyInput.value = "";
    clearKeyBtn.style.display = "none";
    hintEl.style.display = "";
    balanceEl.textContent = "--";
    vscode.postMessage({ type: "saveApiKey", apiKey: "" });
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
      clearKeyBtn.style.display = msg.apiKey ? "" : "none";
      balanceEl.textContent = "--";
      if (msg.apiKey) {
        vscode.postMessage({ type: "fetchBalance" });
      }
      // Also check OpenAI subscription status
      openAiStatusLabel.textContent = "Checking...";
      openAiStatusLabel.className = "subscription-status";
      openAiConnectBtn.classList.add("hidden");
      vscode.postMessage({ type: "checkOpenAiStatus" });
    },

    onSettingsSaved(msg) {
      saveBtn.disabled = false;
      saveBtn.textContent = "Save";
      var hasKey = apiKeyInput.value.trim() !== "";
      clearKeyBtn.style.display = hasKey ? "" : "none";
      if (msg.balance != null) {
        balanceEl.textContent = "$" + msg.balance.toFixed(2);
        hintEl.style.display = "none";
      } else {
        balanceEl.textContent = "--";
        hintEl.style.display = hasKey ? "none" : "";
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

    onOpenAiStatusResult(msg) {
      updateOpenAiUi(msg.connected, msg.unavailable, msg.error);
    },

    onOpenAiConnectStarted(_msg) {
      openAiStatusLabel.textContent = "Waiting for authorization...";
      openAiStatusLabel.className = "subscription-status";
      openAiConnectBtn.disabled = true;
      openAiConnectBtn.textContent = "Waiting...";
      startOpenAiPolling();
    },
  };
}
