// @ts-check
import { initChat, startAssistantMessage, finalizeAssistantMessage, handleToken, resetChat, replayConversation, addMessage, addNotification, addCommandResult, setButtonState, getIsRunning, setStatus, dismissWelcome, startJobTimer, stopJobTimer, setStateHint } from "./chat.js";
import { initCustomSelect, closeAllDropdowns, populateModelSelects } from "./custom-select.js";
import { initContext, renderContext, renderTaskList } from "./context.js";
import { initActivity, renderActivity } from "./activity.js";
import { initSettings } from "./settings.js";
import { initAutocomplete } from "./autocomplete.js";

// ── VS Code API ──────────────────────────────────────

/** @type {ReturnType<typeof acquireVsCodeApi>} */
const vscode = acquireVsCodeApi();

// ── Init Modules ─────────────────────────────────────

initChat();
initContext(vscode);
initActivity(vscode);
const { onSettingsLoaded, onSettingsSaved, onBalanceResult, onSettingsError } = initSettings(vscode);
const { handleResults: handleAutocompleteResults } = initAutocomplete(vscode);

// ── Custom Selects ───────────────────────────────────

const modeSelect = initCustomSelect("mode-select");
const plannerSelect = initCustomSelect("planner-select");
const codeSelect = initCustomSelect("code-select");

// Wire favorite selection to auto-set reasoning on the select
plannerSelect.onChange((value, dataset) => {
  if (dataset && dataset.isFavorite === "true" && dataset.reasoning) {
    plannerSelect.reasoning = dataset.reasoning;
  } else if (!dataset || dataset.isFavorite !== "true") {
    // Non-favorite without submenu interaction keeps DEFAULT
    // (submenu sets reasoning directly)
  }
});

codeSelect.onChange((value, dataset) => {
  if (dataset && dataset.isFavorite === "true" && dataset.reasoning) {
    codeSelect.reasoning = dataset.reasoning;
  }
});

// ── Submit ───────────────────────────────────────────

const promptInput = /** @type {HTMLTextAreaElement} */ (document.getElementById("prompt-input"));
const submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById("submit-btn"));
const cancelBtn = document.getElementById("cancel-btn");
const attachBtn = document.getElementById("attach-btn");

submitBtn.addEventListener("click", () => {
  const task = promptInput.value.trim();
  if (!task || getIsRunning()) return;

  addMessage("user", task);
  promptInput.value = "";

  const reasoningLevel = plannerSelect.reasoning;
  const reasoningLevelCode = codeSelect.reasoning;

  vscode.postMessage({
    type: "submit",
    task,
    mode: modeSelect.value,
    plannerModel: plannerSelect.value,
    codeModel: codeSelect.value || undefined,
    reasoningLevel: reasoningLevel !== "DEFAULT" ? reasoningLevel : undefined,
    reasoningLevelCode: reasoningLevelCode !== "DEFAULT" ? reasoningLevelCode : undefined,
  });
});

promptInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
    submitBtn.click();
  }
});

cancelBtn.addEventListener("click", () => {
  vscode.postMessage({ type: "cancel" });
});

attachBtn.addEventListener("click", () => {
  vscode.postMessage({ type: "attachContext" });
});

// ── Mode Selector ────────────────────────────────────

function updateModeUI() {
  const mode = modeSelect.value;
  if (mode === "LUTZ") {
    plannerSelect.container.style.display = "";
    codeSelect.container.style.display = "";
  } else {
    plannerSelect.container.style.display = "";
    codeSelect.container.style.display = "none";
  }
}

modeSelect.addEventListener("change", updateModeUI);
updateModeUI();

// ── Global Click Handler ─────────────────────────────

const contextMenu = document.getElementById("context-menu");

document.addEventListener("click", (e) => {
  contextMenu.classList.add("hidden");
  closeAllDropdowns();

  const fileLink = e.target.closest("[data-file]");
  if (fileLink) {
    e.preventDefault();
    e.stopPropagation();
    const filePath = fileLink.getAttribute("data-file");
    if (filePath) {
      vscode.postMessage({ type: "openFile", path: filePath });
    }
  }
});

// ── Input Area Drag Resize ───────────────────────────

{
  const inputArea = document.getElementById("input-area");
  const dragHandle = document.getElementById("input-drag-handle");
  let dragging = false;
  let startY = 0;
  let startHeight = 0;

  dragHandle.addEventListener("mousedown", (e) => {
    e.preventDefault();
    dragging = true;
    startY = e.clientY;
    startHeight = inputArea.offsetHeight;
    document.body.style.cursor = "ns-resize";
    document.body.style.userSelect = "none";
  });

  document.addEventListener("mousemove", (e) => {
    if (!dragging) return;
    const delta = startY - e.clientY;
    const newHeight = Math.max(90, Math.min(window.innerHeight * 0.8, startHeight + delta));
    inputArea.style.height = newHeight + "px";
  });

  document.addEventListener("mouseup", () => {
    if (!dragging) return;
    dragging = false;
    document.body.style.cursor = "";
    document.body.style.userSelect = "";
  });
}

// ── Message Router ───────────────────────────────────

window.addEventListener("message", (event) => {
  const msg = event.data;

  switch (msg.type) {
    // Chat events
    case "jobStarted":
      setButtonState(true);
      startJobTimer();
      startAssistantMessage();
      break;

    case "token":
      handleToken(msg);
      break;

    case "notification":
      addNotification(msg.message);
      break;

    case "stateHint":
      if (msg.value && typeof msg.value === "string") {
        setStateHint(String(msg.value));
      }
      break;

    case "jobFinished":
      finalizeAssistantMessage();
      stopJobTimer();
      setButtonState(false);
      setStatus(msg.state === "COMPLETED" ? "Done" : msg.state, 3000);
      break;

    case "jobCancelled":
      stopJobTimer();
      setButtonState(false);
      setStatus("Cancelled", 3000);
      break;

    case "error":
      stopJobTimer();
      addNotification(
        msg.title
          ? `Error: ${msg.title} — ${msg.message}`
          : "Error: " + msg.message
      );
      setButtonState(false);
      break;

    case "commandResult":
      addCommandResult(msg);
      break;

    case "contextBaseline":
      break;

    // Context events
    case "contextUpdate":
      renderContext(msg.data);
      break;

    // Task list events
    case "taskListUpdate":
      renderTaskList(msg.data);
      break;

    // Activity events
    case "activityUpdate":
      console.log("[Brokk] activityUpdate received", msg.session, msg.activity);
      if (msg.session && msg.activity) {
        renderActivity(msg.session, msg.activity);
      } else {
        console.warn("[Brokk] activityUpdate missing session or activity", msg);
      }
      break;

    case "resetChat":
      resetChat(msg.message);
      break;

    case "replayConversation":
      replayConversation(msg.entries);
      break;

    // Models
    case "modelsUpdate":
      if (msg.models) {
        const favorites = msg.favorites || [];
        populateModelSelects(plannerSelect, codeSelect, msg.models, favorites);
      }
      break;

    // Settings events
    case "settingsLoaded":
      onSettingsLoaded(msg);
      break;
    case "settingsSaved":
      onSettingsSaved(msg);
      break;
    case "balanceResult":
      onBalanceResult(msg);
      break;
    case "settingsError":
      onSettingsError(msg);
      break;

    // Autocomplete
    case "autocompleteResults":
      handleAutocompleteResults(msg);
      break;
  }
});
