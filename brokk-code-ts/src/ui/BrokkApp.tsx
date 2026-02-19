import React, { useEffect, useMemo, useRef, useState } from "react";
import { Box, Text, useApp, useInput, useStdout } from "ink";
import { readFile, writeFile } from "node:fs/promises";
import { appendPrompt, clearHistory, loadHistory } from "../state/promptHistory.js";
import { loadSettings, saveSettings } from "../state/settings.js";
import {
  loadLastSessionId,
  saveLastSessionId,
  sessionZipPath
} from "../state/sessionPersistence.js";
import { ExecutorManager, ExecutorError } from "../executor/ExecutorManager.js";
import { ChatPanel, type ChatMessage } from "./components/ChatPanel.js";
import { ContextPanel } from "./components/ContextPanel.js";
import { ModelReasoningModal } from "./components/ModelReasoningModal.js";
import { SelectorModal } from "./components/SelectorModal.js";
import { StatusLine } from "./components/StatusLine.js";
import { TaskListPanel } from "./components/TaskListPanel.js";
import { TokenBar } from "./components/TokenBar.js";
import { UI } from "./theme.js";

export interface BrokkAppOptions {
  workspaceDir: string;
  jarPath?: string;
  executorVersion?: string;
  executorSnapshot?: boolean;
  sessionId?: string;
  resumeSession?: boolean;
  vendor?: string;
}

type Mode = "LUTZ" | "ASK" | "CODE";
type JsonRecord = Record<string, unknown>;
type SelectorKind = "mode" | "plannerReasoning" | "codeReasoning";
type CombinedTarget = "planner" | "code";

interface AppState {
  mode: Mode;
  plannerModel: string;
  codeModel: string;
  reasoning: string;
  codeReasoning: string;
  autoCommit: boolean;
}

interface SelectorState {
  visible: boolean;
  kind: SelectorKind;
  title: string;
  items: string[];
  index: number;
}

interface CombinedSelectorState {
  visible: boolean;
  target: CombinedTarget;
  models: string[];
  modelIndex: number;
  reasoningLevels: string[];
  reasoningIndex: number;
  activePane: "model" | "reasoning";
}

const HELP_LINES = [
  "/context",
  "/context cursor-next|cursor-prev|select|toggle|select-all|clear-selection",
  "/context drop-selected|drop-all|pin-selected|readonly-selected|clear-history|compress-history",
  "/code | /ask | /lutz | /mode [code|ask|lutz]",
  "/model [name] | /model-code [name]",
  "/reasoning [disable|low|medium|high] | /reasoning-code [disable|low|medium|high]",
  "/autocommit on|off|toggle",
  "/history | /history-clear",
  "/task show|hide|next|prev|toggle|delete|add <title>|edit <title>",
  "/info",
  "/help",
  "/quit"
];

function initialState(): AppState {
  return {
    mode: "LUTZ",
    plannerModel: "gpt-5.2",
    codeModel: "gpt-5.2",
    reasoning: "low",
    codeReasoning: "low",
    autoCommit: true
  };
}

function normalizeMode(input?: string): Mode {
  const upper = (input ?? "").trim().toUpperCase();
  if (upper === "CODE") {
    return "CODE";
  }
  if (upper === "ASK") {
    return "ASK";
  }
  return "LUTZ";
}

function reasoningLevels(): string[] {
  return ["disable", "low", "medium", "high"];
}

function fragmentId(fragment: JsonRecord): string {
  return String(fragment.id ?? "").trim();
}

function formatElapsed(totalSeconds: number): string {
  const clamped = Math.max(0, totalSeconds);
  const hours = Math.floor(clamped / 3600);
  const minutes = Math.floor((clamped % 3600) / 60);
  const seconds = clamped % 60;
  if (hours > 0) {
    return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

export function BrokkApp(options: BrokkAppOptions): React.JSX.Element {
  const { exit } = useApp();
  const { stdout } = useStdout();
  const columns = stdout.columns || 120;
  const rows = stdout.rows || 40;
  const executor = useMemo(
    () =>
      new ExecutorManager({
        workspaceDir: options.workspaceDir,
        jarPath: options.jarPath,
        executorVersion: options.executorVersion,
        executorSnapshot: options.executorSnapshot,
        vendor: options.vendor
      }),
    [options.executorSnapshot, options.executorVersion, options.jarPath, options.vendor, options.workspaceDir]
  );

  const [appState, setAppState] = useState<AppState>(initialState());
  const [busy, setBusy] = useState(false);
  const [jobStartedAtMs, setJobStartedAtMs] = useState<number | undefined>(undefined);
  const [helpTick, setHelpTick] = useState(0);
  const [ready, setReady] = useState(false);
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sessionId, setSessionId] = useState<string | undefined>(undefined);
  const [history, setHistory] = useState<string[]>([]);
  const [historyNavIndex, setHistoryNavIndex] = useState(-1);
  const [historyDraft, setHistoryDraft] = useState("");
  const [tasks, setTasks] = useState<JsonRecord[]>([]);
  const [selectedTaskIndex, setSelectedTaskIndex] = useState(0);
  const [taskPanelVisible, setTaskPanelVisible] = useState(false);
  const [fragments, setFragments] = useState<JsonRecord[]>([]);
  const [contextCursorIndex, setContextCursorIndex] = useState(0);
  const [selectedFragmentIds, setSelectedFragmentIds] = useState<Set<string>>(new Set());
  const [contextModalVisible, setContextModalVisible] = useState(false);
  const [currentBranch, setCurrentBranch] = useState("unknown");
  const [statusFragmentDescription, setStatusFragmentDescription] = useState<string | undefined>(undefined);
  const [statusFragmentSizeTokens, setStatusFragmentSizeTokens] = useState<number | undefined>(undefined);
  const [tokenCount, setTokenCount] = useState(0);
  const [availableModels, setAvailableModels] = useState<string[]>([]);
  const [selector, setSelector] = useState<SelectorState>({
    visible: false,
    kind: "mode",
    title: "",
    items: [],
    index: 0
  });
  const [combinedSelector, setCombinedSelector] = useState<CombinedSelectorState>({
    visible: false,
    target: "planner",
    models: [],
    modelIndex: 0,
    reasoningLevels: reasoningLevels(),
    reasoningIndex: 0,
    activePane: "model"
  });
  const isNarrow = columns < UI.smallWidthBreakpoint;
  const appHeight = Math.max(UI.minAppHeight, rows - 1);
  const shellWidth = Math.max(20, columns - UI.horizontalMargin * 2);
  const dockWidth = Math.max(28, Math.min(UI.taskPanelWidth, shellWidth - UI.chatMinWidth));
  const modalWidth = Math.max(30, Math.min(shellWidth, UI.maxCombinedSelectorWidth));
  const rowLimit = rows < 28 ? 5 : rows < 40 ? 7 : 9;
  const contextModalRowLimit = Math.max(6, rows - 9);
  const chatRowLimit = Math.max(3, appHeight - 11);
  const spinnerFrames = ["|", "/", "-", "\\"];

  const shuttingDownRef = useRef(false);
  const currentJobIdRef = useRef<string | undefined>(undefined);
  const pendingPromptRef = useRef<string | undefined>(undefined);
  const readyRef = useRef(false);
  const sessionRef = useRef<string | undefined>(undefined);
  const appStateRef = useRef<AppState>(initialState());

  appStateRef.current = appState;
  readyRef.current = ready;
  sessionRef.current = sessionId;

  const addMessage = (message: ChatMessage): void => {
    setMessages((prev) => [...prev, message]);
  };

  const openSelector = (kind: SelectorKind, title: string, items: string[], selected: string): void => {
    const selectedIndex = Math.max(0, items.indexOf(selected));
    setSelector({
      visible: true,
      kind,
      title,
      items,
      index: selectedIndex
    });
  };

  const closeSelector = (): void => {
    setSelector((prev) => ({ ...prev, visible: false }));
  };

  const openCombinedSelector = (
    target: CombinedTarget,
    models: string[],
    selectedModel: string,
    selectedReasoning: string
  ): void => {
    const modelIndex = Math.max(0, models.indexOf(selectedModel));
    const levels = reasoningLevels();
    const reasoningIndex = Math.max(0, levels.indexOf(selectedReasoning));
    setCombinedSelector({
      visible: true,
      target,
      models,
      modelIndex,
      reasoningLevels: levels,
      reasoningIndex,
      activePane: "model"
    });
  };

  const fetchModelNames = async (): Promise<string[]> => {
    try {
      const modelsData = await executor.getModels();
      const raw = Array.isArray(modelsData.models) ? modelsData.models : [];
      const names: string[] = [];
      for (const model of raw) {
        if (typeof model === "string") {
          const trimmed = model.trim();
          if (trimmed) {
            names.push(trimmed);
          }
        } else if (model && typeof model === "object") {
          const name = String((model as JsonRecord).name ?? "").trim();
          if (name) {
            names.push(name);
          }
        }
      }
      const unique = [...new Set(names)];
      if (unique.length > 0) {
        setAvailableModels(unique);
      }
      return unique;
    } catch {
      return availableModels;
    }
  };

  const refreshContextAndTasks = async (): Promise<void> => {
    if (!readyRef.current) {
      return;
    }

    const [contextData, tasklistData] = await Promise.all([
      executor.getContext().catch(() => ({ fragments: [], usedTokens: 0 })),
      executor.getTasklist().catch(() => ({ tasks: [] }))
    ]);

    const nextFragments = Array.isArray(contextData.fragments)
      ? contextData.fragments.filter((f): f is JsonRecord => !!f && typeof f === "object")
      : [];
    const usedTokens = typeof contextData.usedTokens === "number" ? contextData.usedTokens : 0;
    const branchValue = (contextData as JsonRecord).branch;
    const nextBranch = typeof branchValue === "string" ? branchValue : "unknown";
    const nextTasks = Array.isArray(tasklistData.tasks)
      ? tasklistData.tasks.filter((task): task is JsonRecord => !!task && typeof task === "object")
      : [];

    setFragments(nextFragments);
    setContextCursorIndex((prev) => {
      if (nextFragments.length === 0) {
        return 0;
      }
      return Math.min(prev, nextFragments.length - 1);
    });
    setSelectedFragmentIds((prev) => {
      const valid = new Set<string>();
      const available = new Set(nextFragments.map((f) => fragmentId(f)).filter((id) => id));
      for (const id of prev) {
        if (available.has(id)) {
          valid.add(id);
        }
      }
      return valid;
    });

    setTokenCount(usedTokens);
    setCurrentBranch(nextBranch);
    setTasks(nextTasks);
    setSelectedTaskIndex((prev) => {
      if (nextTasks.length === 0) {
        return 0;
      }
      return Math.min(prev, nextTasks.length - 1);
    });
  };

  const ensureTasklistData = async (): Promise<JsonRecord> => {
    const data = await executor.getTasklist().catch(() => ({ tasks: [] }));
    return (data && typeof data === "object" ? data : { tasks: [] }) as JsonRecord;
  };

  const persistTasklist = async (data: JsonRecord): Promise<void> => {
    const saved = await executor.setTasklist(data);
    const list = Array.isArray(saved.tasks)
      ? saved.tasks.filter((task): task is JsonRecord => !!task && typeof task === "object")
      : [];
    setTasks(list);
    setSelectedTaskIndex((prev) => (list.length === 0 ? 0 : Math.min(prev, list.length - 1)));
  };

  const selectedTask = (): JsonRecord | undefined => {
    if (tasks.length === 0) {
      return undefined;
    }
    return tasks[Math.max(0, Math.min(selectedTaskIndex, tasks.length - 1))];
  };

  const selectedContextIds = (): string[] => [...selectedFragmentIds].filter((id) => id);

  const navigateHistory = (delta: -1 | 1): boolean => {
    if (history.length === 0) {
      return false;
    }

    if (historyNavIndex === -1) {
      if (delta === 1) {
        return false;
      }
      setHistoryDraft(input);
      const newIndex = history.length - 1;
      setHistoryNavIndex(newIndex);
      setInput(history[newIndex] ?? "");
      return true;
    }

    const nextIndex = historyNavIndex + delta;
    if (nextIndex < 0) {
      setHistoryNavIndex(0);
      setInput(history[0] ?? "");
      return true;
    }

    if (nextIndex >= history.length) {
      setHistoryNavIndex(-1);
      setInput(historyDraft);
      return true;
    }

    setHistoryNavIndex(nextIndex);
    setInput(history[nextIndex] ?? "");
    return true;
  };

  const runTaskCommand = async (action: string, title?: string): Promise<void> => {
    if (!readyRef.current) {
      addMessage({ author: "system", text: "Executor is not ready." });
      return;
    }

    if (action === "next") {
      setSelectedTaskIndex((prev) => (tasks.length === 0 ? 0 : Math.min(prev + 1, tasks.length - 1)));
      return;
    }
    if (action === "prev") {
      setSelectedTaskIndex((prev) => (tasks.length === 0 ? 0 : Math.max(prev - 1, 0)));
      return;
    }

    const task = selectedTask();
    const taskId = typeof task?.id === "string" ? task.id.trim() : "";

    if (action === "toggle") {
      if (!taskId) {
        addMessage({ author: "system", text: "No task selected." });
        return;
      }
      const data = await ensureTasklistData();
      const list = Array.isArray(data.tasks) ? data.tasks : [];
      for (const item of list) {
        if (!item || typeof item !== "object") {
          continue;
        }
        const rec = item as JsonRecord;
        if (String(rec.id ?? "").trim() === taskId) {
          rec.done = !Boolean(rec.done);
          break;
        }
      }
      data.tasks = list;
      await persistTasklist(data);
      return;
    }

    if (action === "delete") {
      if (!taskId) {
        addMessage({ author: "system", text: "No task selected." });
        return;
      }
      const data = await ensureTasklistData();
      const list = Array.isArray(data.tasks) ? data.tasks : [];
      data.tasks = list.filter((item) => {
        if (!item || typeof item !== "object") {
          return false;
        }
        const rec = item as JsonRecord;
        return String(rec.id ?? "").trim() !== taskId;
      });
      await persistTasklist(data);
      return;
    }

    if (action === "add") {
      const normalizedTitle = (title ?? "").trim();
      if (!normalizedTitle) {
        addMessage({ author: "system", text: "Usage: /task add <title>" });
        return;
      }
      const data = await ensureTasklistData();
      const list = Array.isArray(data.tasks) ? data.tasks : [];
      list.push({ title: normalizedTitle, text: normalizedTitle, done: false });
      data.tasks = list;
      await persistTasklist(data);
      return;
    }

    if (action === "edit") {
      if (!taskId) {
        addMessage({ author: "system", text: "No task selected." });
        return;
      }
      const normalizedTitle = (title ?? "").trim();
      if (!normalizedTitle) {
        addMessage({ author: "system", text: "Usage: /task edit <title>" });
        return;
      }
      const data = await ensureTasklistData();
      const list = Array.isArray(data.tasks) ? data.tasks : [];
      for (const item of list) {
        if (!item || typeof item !== "object") {
          continue;
        }
        const rec = item as JsonRecord;
        if (String(rec.id ?? "").trim() === taskId) {
          rec.title = normalizedTitle;
          if (!String(rec.text ?? "").trim()) {
            rec.text = normalizedTitle;
          }
          break;
        }
      }
      data.tasks = list;
      await persistTasklist(data);
      return;
    }

    addMessage({ author: "system", text: "Unknown /task command." });
  };

  const runContextCommand = async (action?: string): Promise<void> => {
    if (!readyRef.current) {
      addMessage({ author: "system", text: "Executor is not ready." });
      return;
    }

    if (!action) {
      const nextVisible = !contextModalVisible;
      setContextModalVisible(nextVisible);
      addMessage({ author: "system", text: `Context modal ${nextVisible ? "opened" : "closed"}.` });
      return;
    }

    if (action === "show") {
      setContextModalVisible(true);
      addMessage({ author: "system", text: "Context modal opened." });
      return;
    }

    if (action === "hide") {
      setContextModalVisible(false);
      addMessage({ author: "system", text: "Context modal closed." });
      return;
    }

    if (action === "cursor-next") {
      setContextCursorIndex((prev) => (fragments.length === 0 ? 0 : Math.min(prev + 1, fragments.length - 1)));
      return;
    }

    if (action === "cursor-prev") {
      setContextCursorIndex((prev) => (fragments.length === 0 ? 0 : Math.max(prev - 1, 0)));
      return;
    }

    if (action === "select") {
      const id = fragmentId(fragments[contextCursorIndex] ?? {});
      if (!id) {
        return;
      }
      setSelectedFragmentIds(new Set([id]));
      return;
    }

    if (action === "toggle") {
      const id = fragmentId(fragments[contextCursorIndex] ?? {});
      if (!id) {
        return;
      }
      setSelectedFragmentIds((prev) => {
        const next = new Set(prev);
        if (next.has(id)) {
          next.delete(id);
        } else {
          next.add(id);
        }
        return next;
      });
      return;
    }

    if (action === "select-all") {
      const all = new Set(fragments.map((f) => fragmentId(f)).filter((id) => id));
      setSelectedFragmentIds(all);
      return;
    }

    if (action === "clear-selection") {
      setSelectedFragmentIds(new Set());
      return;
    }

    if (action === "drop-selected") {
      const ids = selectedContextIds();
      if (ids.length === 0) {
        addMessage({ author: "system", text: "No context fragments selected." });
        return;
      }
      await executor.dropContextFragments(ids);
      addMessage({ author: "system", text: `Dropped ${ids.length} fragment(s).` });
      setSelectedFragmentIds(new Set());
      await refreshContextAndTasks();
      return;
    }

    if (action === "pin-selected") {
      const ids = selectedContextIds();
      if (ids.length === 0) {
        addMessage({ author: "system", text: "No context fragments selected." });
        return;
      }
      for (const id of ids) {
        const fragment = fragments.find((f) => fragmentId(f) === id);
        await executor.setContextFragmentPinned(id, !Boolean(fragment?.pinned));
      }
      addMessage({ author: "system", text: `Updated pin state for ${ids.length} fragment(s).` });
      await refreshContextAndTasks();
      return;
    }

    if (action === "readonly-selected") {
      const ids = selectedContextIds();
      if (ids.length === 0) {
        addMessage({ author: "system", text: "No context fragments selected." });
        return;
      }
      for (const id of ids) {
        const fragment = fragments.find((f) => fragmentId(f) === id);
        if (!Boolean(fragment?.editable)) {
          continue;
        }
        await executor.setContextFragmentReadonly(id, !Boolean(fragment?.readonly));
      }
      addMessage({ author: "system", text: `Updated readonly state for selected editable fragment(s).` });
      await refreshContextAndTasks();
      return;
    }

    if (action === "drop-all") {
      await executor.dropAllContext();
      addMessage({ author: "system", text: "Dropped all context fragments." });
      setSelectedFragmentIds(new Set());
      await refreshContextAndTasks();
      return;
    }

    if (action === "clear-history") {
      await executor.clearContextHistory();
      addMessage({ author: "system", text: "Cleared context history." });
      await refreshContextAndTasks();
      return;
    }

    if (action === "compress-history") {
      await executor.compressContextHistory();
      addMessage({ author: "system", text: "Requested context history compression." });
      await refreshContextAndTasks();
      return;
    }

    addMessage({ author: "system", text: "Unknown /context command." });
  };

  const applySelectorChoice = (kind: SelectorKind, choice: string): void => {
    if (kind === "mode") {
      setAppState((prev) => ({ ...prev, mode: normalizeMode(choice) }));
      return;
    }
    if (kind === "plannerReasoning") {
      setAppState((prev) => ({ ...prev, reasoning: choice }));
      return;
    }
    if (kind === "codeReasoning") {
      setAppState((prev) => ({ ...prev, codeReasoning: choice }));
    }
  };

  const shutdown = async (): Promise<void> => {
    if (shuttingDownRef.current) {
      return;
    }
    shuttingDownRef.current = true;

    try {
      if (sessionRef.current && (readyRef.current || executor.checkAlive())) {
        const zipBytes = await executor.downloadSessionZip(sessionRef.current);
        const zipPath = await sessionZipPath(options.workspaceDir, sessionRef.current);
        await writeFile(zipPath, Buffer.from(zipBytes));
      }
    } catch (error) {
      addMessage({ author: "system", text: `[ERROR] Failed to export session: ${String(error)}` });
    }

    await saveSettings({
      theme: "textual-dark",
      prompt_history_size: 50,
      last_model: appStateRef.current.plannerModel,
      last_code_model: appStateRef.current.codeModel,
      last_reasoning_level: appStateRef.current.reasoning,
      last_code_reasoning_level: appStateRef.current.codeReasoning,
      last_auto_commit: appStateRef.current.autoCommit
    }).catch(() => undefined);

    await executor.stop().catch(() => undefined);
  };

  const handleCommand = async (text: string): Promise<boolean> => {
    if (!text.startsWith("/")) {
      return false;
    }

    const parts = text.trim().split(/\s+/);
    const base = (parts[0] ?? "").toLowerCase();

    if (base === "/quit" || base === "/exit") {
      await shutdown();
      exit();
      return true;
    }

    if (base === "/code" || base === "/ask" || base === "/lutz") {
      setAppState((prev) => ({ ...prev, mode: normalizeMode(base.slice(1)) }));
      return true;
    }

    if (base === "/mode") {
      if (parts[1]) {
        setAppState((prev) => ({ ...prev, mode: normalizeMode(parts[1]) }));
      } else {
        openSelector("mode", "Select Mode", ["CODE", "ASK", "LUTZ"], appStateRef.current.mode);
      }
      return true;
    }

    if (base === "/model") {
      if (parts[1]) {
        setAppState((prev) => ({ ...prev, plannerModel: parts[1] as string }));
      } else {
        const fetched = await fetchModelNames();
        const models = fetched.length > 0 ? fetched : [appStateRef.current.plannerModel];
        openCombinedSelector(
          "planner",
          models,
          appStateRef.current.plannerModel,
          appStateRef.current.reasoning
        );
      }
      return true;
    }

    if (base === "/model-code") {
      if (parts[1]) {
        setAppState((prev) => ({ ...prev, codeModel: parts[1] as string }));
      } else {
        const fetched = await fetchModelNames();
        const models = fetched.length > 0 ? fetched : [appStateRef.current.codeModel];
        openCombinedSelector("code", models, appStateRef.current.codeModel, appStateRef.current.codeReasoning);
      }
      return true;
    }

    if (base === "/reasoning") {
      if (parts[1]) {
        setAppState((prev) => ({ ...prev, reasoning: parts[1] as string }));
      } else {
        openSelector("plannerReasoning", "Planner Reasoning", reasoningLevels(), appStateRef.current.reasoning);
      }
      return true;
    }

    if (base === "/reasoning-code") {
      if (parts[1]) {
        setAppState((prev) => ({ ...prev, codeReasoning: parts[1] as string }));
      } else {
        openSelector("codeReasoning", "Code Reasoning", reasoningLevels(), appStateRef.current.codeReasoning);
      }
      return true;
    }

    if (base === "/autocommit") {
      if (!parts[1]) {
        addMessage({
          author: "system",
          text: `Auto-commit: ${appStateRef.current.autoCommit ? "ON" : "OFF"} (usage: /autocommit on|off|toggle)`
        });
        return true;
      }
      const arg = parts[1].toLowerCase();
      setAppState((prev) => {
        if (arg === "toggle") {
          return { ...prev, autoCommit: !prev.autoCommit };
        }
        if (["on", "true", "1", "yes"].includes(arg)) {
          return { ...prev, autoCommit: true };
        }
        if (["off", "false", "0", "no"].includes(arg)) {
          return { ...prev, autoCommit: false };
        }
        addMessage({ author: "system", text: "Usage: /autocommit on|off|toggle" });
        return prev;
      });
      return true;
    }

    if (base === "/history") {
      if (history.length === 0) {
        addMessage({ author: "system", text: "Prompt history is empty." });
      } else {
        const formatted = history.map((prompt, idx) => `${idx + 1}. ${prompt}`).join("\n");
        addMessage({ author: "system", text: `Recent prompts:\n${formatted}` });
      }
      return true;
    }

    if (base === "/history-clear") {
      await clearHistory(options.workspaceDir).catch(() => undefined);
      setHistory([]);
      addMessage({ author: "system", text: "Prompt history cleared." });
      return true;
    }

    if (base === "/task") {
      if (!parts[1]) {
        const nextVisible = !taskPanelVisible;
        setTaskPanelVisible(nextVisible);
        addMessage({
          author: "system",
          text: `Task panel ${nextVisible ? "shown" : "hidden"} (${tasks.length} item(s)).`
        });
      } else {
        const action = parts[1].toLowerCase();
        if (action === "show") {
          setTaskPanelVisible(true);
          return true;
        }
        if (action === "hide") {
          setTaskPanelVisible(false);
          return true;
        }
        const title = parts.slice(2).join(" ");
        await runTaskCommand(action, title);
      }
      return true;
    }

    if (base === "/context") {
      await runContextCommand(parts[1]?.toLowerCase());
      return true;
    }

    if (base === "/info") {
      addMessage({
        author: "system",
        text:
          `Status: ${readyRef.current ? "Ready" : "Initializing"}\n` +
          `Workspace: ${options.workspaceDir}\n` +
          `Session: ${sessionRef.current ?? "n/a"}\n` +
          `Mode: ${appStateRef.current.mode}\n` +
          `Planner Model: ${appStateRef.current.plannerModel} (${appStateRef.current.reasoning})\n` +
          `Code Model: ${appStateRef.current.codeModel} (${appStateRef.current.codeReasoning})\n` +
          `Auto-commit: ${appStateRef.current.autoCommit ? "ON" : "OFF"}`
      });
      return true;
    }

    if (base === "/help") {
      addMessage({ author: "system", text: `Available commands:\n${HELP_LINES.join("\n")}` });
      return true;
    }

    addMessage({ author: "system", text: `Unknown command: ${base}. Type /help.` });
    return true;
  };

  const runJob = async (prompt: string): Promise<void> => {
    setBusy(true);
    if (jobStartedAtMs === undefined) {
      setJobStartedAtMs(Date.now());
    }
    try {
      const jobId = await executor.submitJob({
        taskInput: prompt,
        plannerModel: appStateRef.current.plannerModel,
        codeModel: appStateRef.current.codeModel,
        reasoningLevel: appStateRef.current.reasoning,
        reasoningLevelCode: appStateRef.current.codeReasoning,
        mode: appStateRef.current.mode,
        autoCommit: appStateRef.current.autoCommit,
        sessionId: sessionRef.current
      });
      currentJobIdRef.current = jobId;

      let currentBuffer = "";
      let currentIsReasoning = false;
      const flushAssistantBuffer = (): void => {
        const content = currentBuffer.trim();
        if (!content) {
          currentBuffer = "";
          currentIsReasoning = false;
          return;
        }
        addMessage({
          author: "assistant",
          text: currentIsReasoning ? `### Thinking\n\n${content}` : content
        });
        currentBuffer = "";
        currentIsReasoning = false;
      };
      for await (const event of executor.streamEvents(jobId)) {
        const eventType = event.type;
        const data = event.data && typeof event.data === "object" ? (event.data as JsonRecord) : {};

        if (eventType === "LLM_TOKEN" && typeof data.token === "string") {
          const isReasoning = Boolean(data.isReasoning);
          const isNewMessage = Boolean(data.isNewMessage);
          const isTerminal = Boolean(data.isTerminal);
          const shouldStartNew =
            isNewMessage || (currentBuffer.length > 0 && currentIsReasoning !== isReasoning);

          if (shouldStartNew) {
            flushAssistantBuffer();
          }
          currentIsReasoning = isReasoning;
          currentBuffer += data.token;
          if (isTerminal) {
            flushAssistantBuffer();
          }
        } else if (eventType === "NOTIFICATION") {
          flushAssistantBuffer();
          const level = String(data.level ?? "INFO");
          const message = String(data.message ?? "");
          if (message) {
            addMessage({ author: "system", text: `[${level}] ${message}` });
          }
        } else if (eventType === "ERROR") {
          flushAssistantBuffer();
          addMessage({ author: "system", text: `[ERROR] ${String(data.message ?? "Unknown error")}` });
        } else if (eventType === "STATE_HINT") {
          const name = String(data.name ?? "");
          if (name === "contextHistoryUpdated" || name === "workspaceUpdated") {
            await refreshContextAndTasks();
          }
        }
      }
      flushAssistantBuffer();
    } catch (error) {
      addMessage({ author: "system", text: `[ERROR] ${error instanceof Error ? error.message : String(error)}` });
    } finally {
      currentJobIdRef.current = undefined;
      await refreshContextAndTasks().catch(() => undefined);

      const pending = pendingPromptRef.current;
      pendingPromptRef.current = undefined;
      if (pending) {
        await runJob(pending);
      } else {
        setBusy(false);
        setJobStartedAtMs(undefined);
      }
    }
  };

  const submitPrompt = async (): Promise<void> => {
    const prompt = input.trim();
    if (!prompt) {
      return;
    }

    setInput("");
    setHistoryNavIndex(-1);
    setHistoryDraft("");
    addMessage({ author: "user", text: prompt });

    if (await handleCommand(prompt)) {
      return;
    }

    await appendPrompt(options.workspaceDir, prompt).catch(() => undefined);
    setHistory((prev) => {
      const next = [...prev, prompt];
      return next.length > 100 ? next.slice(next.length - 100) : next;
    });

    if (busy && currentJobIdRef.current) {
      pendingPromptRef.current = prompt;
      addMessage({ author: "system", text: "Interrupting current job to start new request..." });
      await executor.cancelJob(currentJobIdRef.current);
      return;
    }

    await runJob(prompt);
  };

  useInput((value, key) => {
    if (contextModalVisible) {
      if (key.escape) {
        setContextModalVisible(false);
        return;
      }
      if (key.downArrow) {
        setContextCursorIndex((prev) => (fragments.length === 0 ? 0 : Math.min(prev + 1, fragments.length - 1)));
        return;
      }
      if (key.upArrow) {
        setContextCursorIndex((prev) => (fragments.length === 0 ? 0 : Math.max(prev - 1, 0)));
        return;
      }
      if (value === " ") {
        const id = fragmentId(fragments[contextCursorIndex] ?? {});
        if (id) {
          setSelectedFragmentIds((prev) => {
            const next = new Set(prev);
            if (next.has(id)) {
              next.delete(id);
            } else {
              next.add(id);
            }
            return next;
          });
        }
        return;
      }
      if (key.return) {
        const id = fragmentId(fragments[contextCursorIndex] ?? {});
        if (id) {
          setSelectedFragmentIds(new Set([id]));
        }
        return;
      }
      if (value?.toLowerCase() === "d") {
        void runContextCommand("drop-selected");
        return;
      }
      if (value?.toLowerCase() === "p") {
        void runContextCommand("pin-selected");
        return;
      }
      if (value?.toLowerCase() === "r") {
        void runContextCommand("readonly-selected");
        return;
      }

      // Full-screen modal: swallow all other input while open.
      return;
    }

    if (combinedSelector.visible) {
      if (key.escape) {
        setCombinedSelector((prev) => ({ ...prev, visible: false }));
        return;
      }

      if (key.leftArrow || key.rightArrow || key.tab) {
        setCombinedSelector((prev) => ({
          ...prev,
          activePane: prev.activePane === "model" ? "reasoning" : "model"
        }));
        return;
      }

      if (key.downArrow) {
        setCombinedSelector((prev) => {
          if (prev.activePane === "model") {
            const nextIndex = prev.models.length === 0 ? 0 : (prev.modelIndex + 1) % prev.models.length;
            return { ...prev, modelIndex: nextIndex };
          }
          const nextIndex =
            prev.reasoningLevels.length === 0 ? 0 : (prev.reasoningIndex + 1) % prev.reasoningLevels.length;
          return { ...prev, reasoningIndex: nextIndex };
        });
        return;
      }

      if (key.upArrow) {
        setCombinedSelector((prev) => {
          if (prev.activePane === "model") {
            const nextIndex =
              prev.models.length === 0 ? 0 : (prev.modelIndex - 1 + prev.models.length) % prev.models.length;
            return { ...prev, modelIndex: nextIndex };
          }
          const nextIndex =
            prev.reasoningLevels.length === 0
              ? 0
              : (prev.reasoningIndex - 1 + prev.reasoningLevels.length) % prev.reasoningLevels.length;
          return { ...prev, reasoningIndex: nextIndex };
        });
        return;
      }

      if (key.return) {
        if (combinedSelector.activePane === "model") {
          setCombinedSelector((prev) => ({ ...prev, activePane: "reasoning" }));
          return;
        }

        const model = combinedSelector.models[combinedSelector.modelIndex];
        const reasoning = combinedSelector.reasoningLevels[combinedSelector.reasoningIndex];
        if (model && reasoning) {
          if (combinedSelector.target === "planner") {
            setAppState((prev) => ({ ...prev, plannerModel: model, reasoning }));
          } else {
            setAppState((prev) => ({ ...prev, codeModel: model, codeReasoning: reasoning }));
          }
        }
        setCombinedSelector((prev) => ({ ...prev, visible: false }));
        return;
      }
    }

    if (selector.visible) {
      if (key.escape) {
        closeSelector();
        return;
      }
      if (key.downArrow || (key.tab && !key.shift)) {
        setSelector((prev) => ({
          ...prev,
          index: prev.items.length === 0 ? 0 : (prev.index + 1) % prev.items.length
        }));
        return;
      }
      if (key.upArrow || (key.tab && key.shift)) {
        setSelector((prev) => ({
          ...prev,
          index: prev.items.length === 0 ? 0 : (prev.index - 1 + prev.items.length) % prev.items.length
        }));
        return;
      }
      if (key.return) {
        const choice = selector.items[selector.index];
        if (choice) {
          applySelectorChoice(selector.kind, choice);
        }
        closeSelector();
        return;
      }
    }

    if (key.ctrl && value === "c") {
      void (async () => {
        await shutdown();
        exit();
      })();
      return;
    }

    if (key.upArrow) {
      if (navigateHistory(-1)) {
        return;
      }
      if (!input.trim() && !selector.visible && !contextModalVisible && taskPanelVisible) {
        setSelectedTaskIndex((prev) => (tasks.length === 0 ? 0 : Math.max(prev - 1, 0)));
        return;
      }
    }

    if (key.downArrow) {
      if (navigateHistory(1)) {
        return;
      }
      if (!input.trim() && !selector.visible && !contextModalVisible && taskPanelVisible) {
        setSelectedTaskIndex((prev) => (tasks.length === 0 ? 0 : Math.min(prev + 1, tasks.length - 1)));
        return;
      }
    }

    if (key.return) {
      void submitPrompt();
      return;
    }
    if (key.backspace || key.delete) {
      if (historyNavIndex !== -1) {
        setHistoryNavIndex(-1);
        setHistoryDraft("");
      }
      setInput((prev) => prev.slice(0, -1));
      return;
    }
    if (value && !key.ctrl && !key.meta) {
      if (historyNavIndex !== -1) {
        setHistoryNavIndex(-1);
        setHistoryDraft("");
      }
      setInput((prev) => `${prev}${value}`);
    }
  });

  useEffect(() => {
    let mounted = true;

    const startup = async (): Promise<void> => {
      try {
        const settings = await loadSettings();
        if (!mounted) {
          return;
        }

        setAppState((prev) => ({
          ...prev,
          plannerModel: settings.last_model?.trim() || prev.plannerModel,
          codeModel: settings.last_code_model?.trim() || prev.codeModel,
          reasoning: settings.last_reasoning_level?.trim() || prev.reasoning,
          codeReasoning: settings.last_code_reasoning_level?.trim() || prev.codeReasoning,
          autoCommit: settings.last_auto_commit ?? prev.autoCommit
        }));

        const promptHistory = await loadHistory(options.workspaceDir);
        setHistory(promptHistory);

        addMessage({ author: "system", text: "Starting Brokk executor..." });
        await executor.start();
        await fetchModelNames();

        try {
          const live = await executor.getHealthLive();
          addMessage({
            author: "system",
            text: `Connected to executor ${String(live.execId ?? "unknown")} (version: ${String(
              live.version ?? "unknown"
            )}, protocol: ${String(live.protocolVersion ?? "unknown")})`
          });
        } catch {
          // no-op
        }

        let sessionToResume = options.sessionId;
        if (!sessionToResume && options.resumeSession) {
          sessionToResume = await loadLastSessionId(options.workspaceDir);
        }

        let resumed = false;
        if (sessionToResume) {
          const zipPath = await sessionZipPath(options.workspaceDir, sessionToResume);
          try {
            const zipBytes = await readFile(zipPath);
            addMessage({ author: "system", text: `Resuming session ${sessionToResume}...` });
            await executor.importSessionZip(zipBytes, sessionToResume);
            setSessionId(sessionToResume);
            resumed = true;
          } catch {
            // Fall through to create a new session
          }
        }

        if (!resumed) {
          const createdSessionId = await executor.createSession("TUI Session");
          setSessionId(createdSessionId);
        }

        if (executor.sessionId) {
          await saveLastSessionId(options.workspaceDir, executor.sessionId).catch(() => undefined);
        }

        const isReady = await executor.waitReady();
        if (!isReady) {
          throw new ExecutorError("Executor failed to become ready (timeout).");
        }
        setReady(true);
        addMessage({ author: "system", text: "Ready!" });

        await refreshContextAndTasks();
      } catch (error) {
        addMessage({ author: "system", text: `[ERROR] ${error instanceof Error ? error.message : String(error)}` });
      }
    };

    void startup();

    const pollTimer = setInterval(() => {
      void refreshContextAndTasks().catch(() => undefined);
    }, 12_000);

    const monitorTimer = setInterval(() => {
      if (readyRef.current && !executor.checkAlive()) {
        addMessage({ author: "system", text: "[ERROR] Executor process crashed unexpectedly." });
      }
    }, 2_000);

    return () => {
      mounted = false;
      clearInterval(pollTimer);
      clearInterval(monitorTimer);
      void shutdown();
    };
  }, []);

  useEffect(() => {
    if (!busy || jobStartedAtMs === undefined) {
      return;
    }
    const interval = setInterval(() => {
      setHelpTick((prev) => prev + 1);
    }, 200);
    return () => {
      clearInterval(interval);
    };
  }, [busy, jobStartedAtMs]);

  if (contextModalVisible) {
    return (
      <Box flexDirection="column" width="100%" height={appHeight}>
        <Box borderStyle="round" flexDirection="column" paddingX={UI.modalPaddingX} paddingY={UI.modalPaddingY} flexGrow={1} flexShrink={1} marginX={UI.horizontalMargin}>
          <Text bold>Context</Text>
          <Text dimColor>
            Session: {sessionId ?? "n/a"} | Fragments: {fragments.length} | Selected: {selectedFragmentIds.size}
          </Text>
          <ContextPanel
            fragments={fragments}
            selectedFragmentIds={selectedFragmentIds}
            cursorIndex={contextCursorIndex}
            tokenCount={tokenCount}
            width={Math.max(20, shellWidth - 2)}
            rowLimit={contextModalRowLimit}
            framed={false}
            showHeader={false}
          />
        </Box>
        <Box marginX={UI.horizontalMargin}>
          <Text dimColor>Esc close | Up/Down move | Space toggle | Enter select-only | d drop | p pin | r readonly</Text>
        </Box>
      </Box>
    );
  }

  const spinner = spinnerFrames[helpTick % spinnerFrames.length] ?? "|";
  const elapsedSeconds = jobStartedAtMs === undefined ? 0 : Math.floor((Date.now() - jobStartedAtMs) / 1000);
  const leftHelp = busy ? `${spinner}  Elapsed: ${formatElapsed(elapsedSeconds)}` : "";

  return (
    <Box flexDirection="column" width="100%" height={appHeight}>
      <Box marginX={UI.horizontalMargin} flexDirection={isNarrow ? "column" : "row"} flexGrow={1} flexShrink={1}>
        <Box flexGrow={1} marginRight={!isNarrow && taskPanelVisible ? 1 : 0} flexShrink={1} minWidth={UI.chatMinWidth} flexDirection="column">
          <ChatPanel messages={messages} rowLimit={chatRowLimit} />
          <TokenBar used={tokenCount} budget={200_000} />
        </Box>
        {taskPanelVisible ? (
          <Box flexDirection="column" width={isNarrow ? shellWidth : dockWidth} marginTop={isNarrow ? 1 : 0} flexShrink={0}>
            <TaskListPanel
              tasks={tasks}
              selectedIndex={selectedTaskIndex}
              width={isNarrow ? shellWidth : dockWidth}
              rowLimit={rowLimit}
            />
            <Text dimColor>
              Task: {tasks.length === 0 ? "none" : `${selectedTaskIndex + 1}/${tasks.length}`}
            </Text>
          </Box>
        ) : null}
      </Box>
      <Box marginX={UI.horizontalMargin}>
        <StatusLine
          mode={appState.mode}
          model={appState.plannerModel}
          reasoning={appState.reasoning}
          workspace={options.workspaceDir}
          branch={currentBranch}
          fragmentDescription={statusFragmentDescription}
          fragmentSizeTokens={statusFragmentSizeTokens}
          maxWidth={shellWidth}
        />
      </Box>
      <Box borderStyle="round" paddingX={1} marginTop={1} marginX={UI.horizontalMargin} flexShrink={0}>
        <Text>
          <Text color="magenta">&gt;</Text> {input}
        </Text>
      </Box>
      <Box marginX={UI.horizontalMargin}>
        <Box justifyContent="space-between" width="100%">
          <Text dimColor>{leftHelp}</Text>
          <Text dimColor>Enter: Submit  Shift+Enter: Newline  Up/Down: History  /commands</Text>
        </Box>
      </Box>
      <SelectorModal
        title={selector.title}
        items={selector.items}
        selectedIndex={selector.index}
        visible={selector.visible}
        width={Math.min(modalWidth, UI.maxSelectorWidth)}
        rowLimit={rowLimit}
      />
      <ModelReasoningModal
        visible={combinedSelector.visible}
        targetLabel={combinedSelector.target === "planner" ? "Planner" : "Code"}
        models={combinedSelector.models}
        modelIndex={combinedSelector.modelIndex}
        reasoningLevels={combinedSelector.reasoningLevels}
        reasoningIndex={combinedSelector.reasoningIndex}
        activePane={combinedSelector.activePane}
        width={modalWidth}
        rowLimit={rowLimit}
      />
    </Box>
  );
}
