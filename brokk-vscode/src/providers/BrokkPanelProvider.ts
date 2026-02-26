import * as vscode from "vscode";
import { BrokkClient } from "../executor/client";
import { EventStreamManager } from "../executor/EventStreamManager";
import type {
  LlmTokenData,
  NotificationData,
  ErrorData,
  ConfirmRequestData,
  ContextBaselineData,
  CommandResultData,
  StateHintData,
  JobEvent,
  DiffEntry,
  FavoriteModelInfo,
  ModelInfo,
} from "../types";
import { DiffContentProvider, parseUnifiedDiff } from "./DiffContentProvider";
import { getPanelHtml } from "./panelHtml";
import { handleSettingsMessage } from "./settingsManager";

export class BrokkPanelProvider implements vscode.WebviewViewProvider {
  public static readonly viewType = "brokk-panel";

  private view?: vscode.WebviewView;
  private client: BrokkClient | null = null;
  private streamManager: EventStreamManager | null = null;
  private currentJobId: string | null = null;
  private eventSub?: { dispose: () => void };
  private finishedSub?: { dispose: () => void };
  private lastContextJson = "";
  private lastTaskListJson = "";
  private lastActivityJson = "";
  private log?: (msg: string) => void;
  private contextRefreshTimer: ReturnType<typeof setTimeout> | null = null;
  private contextRefreshInFlight = false;
  private taskListRefreshTimer: ReturnType<typeof setTimeout> | null = null;
  private taskListRefreshInFlight = false;
  private activityRefreshTimer: ReturnType<typeof setTimeout> | null = null;
  private activityRefreshInFlight = false;
  private readonly extensionUri: vscode.Uri;
  private readonly diffProvider: DiffContentProvider;
  private cachedModels: ModelInfo[] = [];
  private cachedFavorites: FavoriteModelInfo[] = [];
  private cachedConnectionStatus: { status: string; detail: string } | null = null;

  constructor(extensionUri: vscode.Uri, diffProvider: DiffContentProvider) {
    this.extensionUri = extensionUri;
    this.diffProvider = diffProvider;
  }

  setLog(logFn: (msg: string) => void) {
    this.log = logFn;
  }

  setClient(client: BrokkClient) {
    this.client = client;
    this.refreshContext();
    this.refreshTaskList();
    this.refreshActivity();
    this.refreshModels();
  }

  private async refreshModels() {
    if (!this.client) return;
    try {
      const [modelsResp, favoritesResp] = await Promise.all([
        this.client.getModels(),
        this.client.getFavorites().catch(() => ({ favorites: [] })),
      ]);
      const modelInfos: ModelInfo[] = modelsResp.models.map((m) =>
        typeof m === "string"
          ? { name: m, location: "", supportsReasoningEffort: false, supportsReasoningDisable: false }
          : m
      );
      this.cachedModels = modelInfos;
      this.cachedFavorites = favoritesResp.favorites;
      this.sendToWebview("modelsUpdate", {
        models: modelInfos,
        favorites: favoritesResp.favorites,
      });
    } catch (e) {
      this.log?.(`Failed to fetch models: ${e}`);
    }
  }

  setStreamManager(streamManager: EventStreamManager) {
    this.eventSub?.dispose();
    this.finishedSub?.dispose();

    this.streamManager = streamManager;

    this.eventSub = streamManager.onEvent((event) =>
      this.processEvent(event)
    );
    this.finishedSub = streamManager.onJobFinished((state) => {
      this.sendToWebview("jobFinished", { state });
      this.currentJobId = null;
      this.refreshActivity();
    });
  }

  /** Send a connection-status update to the webview (e.g. "noWorkspace", "starting", "failed"). */
  sendConnectionStatus(status: string, detail?: string) {
    this.cachedConnectionStatus = { status, detail: detail ?? "" };
    this.sendToWebview("connectionStatus", this.cachedConnectionStatus);
  }

  resolveWebviewView(
    webviewView: vscode.WebviewView,
    _context: vscode.WebviewViewResolveContext,
    _token: vscode.CancellationToken
  ) {
    this.view = webviewView;

    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [
        vscode.Uri.joinPath(this.extensionUri, "media"),
      ],
    };

    webviewView.webview.html = getPanelHtml(webviewView.webview, this.extensionUri);

    webviewView.webview.onDidReceiveMessage(async (msg) => {
      await this.handleMessage(msg);
    });

    webviewView.onDidDispose(() => {
      this.log?.("[Panel] webview disposed — clearing timers");
      this.view = undefined;
      if (this.contextRefreshTimer) {
        clearTimeout(this.contextRefreshTimer);
        this.contextRefreshTimer = null;
      }
      if (this.taskListRefreshTimer) {
        clearTimeout(this.taskListRefreshTimer);
        this.taskListRefreshTimer = null;
      }
      if (this.activityRefreshTimer) {
        clearTimeout(this.activityRefreshTimer);
        this.activityRefreshTimer = null;
      }
    });

    // Cached state is replayed when the webview sends "webviewReady" (see handleMessage).
  }

  // ── Context ──────────────────────────────────────────

  /**
   * Debounced context refresh — coalesces rapid-fire calls into a single HTTP request.
   * Safe to call frequently (e.g., from multiple STATE_HINT events in a single batch).
   */
  refreshContext(delayMs = 250) {
    if (this.contextRefreshTimer) clearTimeout(this.contextRefreshTimer);
    this.contextRefreshTimer = setTimeout(() => {
      this.contextRefreshTimer = null;
      this.doRefreshContext();
    }, delayMs);
  }

  private async doRefreshContext() {
    if (!this.client) return;
    if (this.contextRefreshInFlight) {
      this.log?.("[Context] refresh skipped — previous request still in-flight");
      return;
    }
    this.contextRefreshInFlight = true;
    try {
      const ctx = await this.client.getContext();
      const json = JSON.stringify(ctx);
      if (json !== this.lastContextJson) {
        this.log?.(`[Context] refresh: ${ctx.fragments.length} fragments, ${ctx.usedTokens} tokens`);
        this.lastContextJson = json;
        this.view?.webview.postMessage({ type: "contextUpdate", data: ctx });
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      this.log?.(`[Context] refresh error: ${message}`);
    } finally {
      this.contextRefreshInFlight = false;
    }
  }

  // ── Task List ────────────────────────────────────────

  /**
   * Debounced task list refresh — coalesces rapid-fire calls.
   */
  refreshTaskList(delayMs = 250) {
    if (this.taskListRefreshTimer) clearTimeout(this.taskListRefreshTimer);
    this.taskListRefreshTimer = setTimeout(() => {
      this.taskListRefreshTimer = null;
      this.doRefreshTaskList();
    }, delayMs);
  }

  private async doRefreshTaskList() {
    if (!this.client) return;
    if (this.taskListRefreshInFlight) {
      this.log?.("[TaskList] refresh skipped — previous request still in-flight");
      return;
    }
    this.taskListRefreshInFlight = true;
    try {
      const taskList = await this.client.getTaskList();
      const json = JSON.stringify(taskList);
      if (json !== this.lastTaskListJson) {
        this.log?.(`[TaskList] refresh: ${taskList?.tasks?.length ?? 0} tasks`);
        this.lastTaskListJson = json;
        this.view?.webview.postMessage({ type: "taskListUpdate", data: taskList });
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      this.log?.(`[TaskList] refresh error: ${message}`);
    } finally {
      this.taskListRefreshInFlight = false;
    }
  }

  // ── Activity ────────────────────────────────────────

  /**
   * Debounced activity refresh — coalesces rapid-fire calls.
   */
  refreshActivity(delayMs = 500) {
    if (this.activityRefreshTimer) clearTimeout(this.activityRefreshTimer);
    this.activityRefreshTimer = setTimeout(() => {
      this.activityRefreshTimer = null;
      this.doRefreshActivity();
    }, delayMs);
  }

  private async doRefreshActivity() {
    if (!this.client) return;
    if (this.activityRefreshInFlight) {
      this.log?.("[Activity] refresh skipped — previous request still in-flight");
      return;
    }
    this.activityRefreshInFlight = true;
    try {
      const [session, activity] = await Promise.all([
        this.client.getCurrentSession(),
        this.client.getActivity(),
      ]);
      const json = JSON.stringify({ session, activity });
      if (json !== this.lastActivityJson) {
        this.log?.(`[Activity] refresh: ${activity.groups.length} groups`);
        this.lastActivityJson = json;
        this.view?.webview.postMessage({ type: "activityUpdate", session, activity });
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      this.log?.(`[Activity] refresh error: ${message}`);
    } finally {
      this.activityRefreshInFlight = false;
    }
  }

  // ── Message Handling ─────────────────────────────────

  private async handleMessage(msg: { type: string; [key: string]: unknown }) {
    // Webview signals it's ready — replay all cached state
    if (msg.type === "webviewReady") {
      if (this.lastContextJson) {
        this.sendToWebview("contextUpdate", { data: JSON.parse(this.lastContextJson) });
      }
      if (this.lastTaskListJson) {
        this.sendToWebview("taskListUpdate", { data: JSON.parse(this.lastTaskListJson) });
      }
      if (this.lastActivityJson) {
        this.sendToWebview("activityUpdate", JSON.parse(this.lastActivityJson));
      }
      if (this.cachedModels.length > 0) {
        this.sendToWebview("modelsUpdate", { models: this.cachedModels, favorites: this.cachedFavorites });
      }
      if (this.cachedConnectionStatus) {
        this.sendToWebview("connectionStatus", this.cachedConnectionStatus);
      }
      return;
    }

    // Chat messages don't require client
    if (msg.type === "submit" || msg.type === "cancel") {
      await this.handleChatMessage(msg);
      return;
    }

    // Settings messages don't require executor client
    if (msg.type === "loadSettings" || msg.type === "saveApiKey" || msg.type === "fetchBalance") {
      await handleSettingsMessage(msg, this.sendToWebview.bind(this));
      return;
    }

    // Open file in editor (doesn't require client)
    if (msg.type === "openFile") {
      const filePath = msg.path as string;
      if (filePath) {
        const wsFolder = vscode.workspace.workspaceFolders?.[0];
        const uri = filePath.startsWith("/")
          ? vscode.Uri.file(filePath)
          : wsFolder
            ? vscode.Uri.joinPath(wsFolder.uri, filePath)
            : vscode.Uri.file(filePath);
        try {
          const doc = await vscode.workspace.openTextDocument(uri);
          await vscode.window.showTextDocument(doc, { preview: false });
        } catch {
          vscode.window.showWarningMessage(`Could not open file: ${filePath}`);
        }
      }
      return;
    }

    // Autocomplete messages require client
    if (msg.type === "autocomplete") {
      if (this.client) {
        try {
          const results = await this.client.getCompletions(msg.query as string);
          this.sendToWebview("autocompleteResults", { completions: results.completions });
        } catch (err: unknown) {
          this.log?.(`[Autocomplete] error: ${err instanceof Error ? err.message : String(err)}`);
        }
      }
      return;
    }

    // Context messages require client
    if (!this.client) return;
    try {
      switch (msg.type) {
        case "drop":
          await this.client.dropFragments(msg.fragmentIds as string[]);
          break;
        case "dropOthers":
          await this.handleDropOthers(msg.keepId as string);
          break;
        case "pin":
          await this.client.pinFragment(
            msg.fragmentId as string,
            msg.pinned as boolean
          );
          break;
        case "readonly":
          await this.client.readonlyFragment(
            msg.fragmentId as string,
            msg.readonly as boolean
          );
          break;
        case "compressHistory":
          await this.client.compressHistory();
          break;
        case "clearHistory":
          await this.client.clearHistory();
          break;
        case "dropAll":
          await this.client.dropAll();
          break;
        case "addFile":
          await this.promptAddFile();
          break;
        case "addFiles":
          await this.client.addFiles(msg.paths as string[]);
          break;
        case "addClasses":
          await this.client.addClasses(msg.names as string[]);
          break;
        case "addMethods":
          await this.client.addMethods(msg.names as string[]);
          break;
        case "addText":
          await this.client.addText(msg.text as string);
          break;
        case "attachContext":
          await this.showAttachContextMenu();
          break;
        case "activityAction":
          await this.handleActivityAction(
            msg.action as string,
            msg.contextId as string
          );
          break;
        case "switchSession":
          await this.promptSwitchSession();
          break;
        case "undoStep":
          await this.handleUndoRedo("undo");
          break;
        case "redoStep":
          await this.handleUndoRedo("redo");
          break;
      }
      this.refreshContext();
      this.refreshTaskList();
      this.refreshActivity();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`Brokk: ${message}`);
    }
  }

  private async handleUndoRedo(direction: "undo" | "redo") {
    if (!this.client) return;
    try {
      if (direction === "undo") {
        await this.client.undoStep();
      } else {
        await this.client.redoStep();
      }
      // Replay conversation like Chrome.contextChanged() does
      if (!this.currentJobId) {
        try {
          const conversation = await this.client.getConversation();
          this.sendToWebview("replayConversation", {
            entries: conversation.entries,
          });
        } catch {
          this.sendToWebview("resetChat", {
            message: direction === "undo" ? "Undid most recent step" : "Redid step",
          });
        }
      }
      this.refreshContext();
      this.refreshTaskList();
      this.refreshActivity();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`Brokk: ${message}`);
    }
  }

  private async handleActivityAction(action: string, contextId: string) {
    if (!this.client) return;

    let label: string;
    switch (action) {
      case "undo":
        await this.client.undoToContext(contextId);
        label = "Undid to historical context";
        break;
      case "copyContext":
        await this.client.copyContext(contextId);
        label = "Copied context from history";
        break;
      case "copyContextHistory":
        await this.client.copyContextWithHistory(contextId);
        label = "Copied context and history";
        break;
      case "newSession":
        await this.client.newSessionFromContext(contextId);
        label = "Created new session from workspace";
        break;
      case "showDiff": {
        const result = await this.client.getContextDiff(contextId);
        await this.openDiffsInEditor(contextId, result.diffs);
        return; // Don't refresh context/activity for diff display
      }
      default:
        return;
    }

    // Mirror Chrome.contextChanged(): after context actions, fetch the new
    // context's task history and replay it into the chat panel — just like
    // the MOP does with setLlmAndHistoryOutput.
    if (!this.currentJobId) {
      try {
        const conversation = await this.client.getConversation();
        this.sendToWebview("replayConversation", {
          entries: conversation.entries,
        });
      } catch {
        // Fallback: just clear with a notification
        this.sendToWebview("resetChat", { message: label });
      }
    }
  }

  private async openDiffsInEditor(contextId: string, diffs: DiffEntry[]) {
    if (!diffs || diffs.length === 0) {
      vscode.window.showInformationMessage("No changes in this context step.");
      return;
    }

    for (const diff of diffs) {
      const { before, after } = parseUnifiedDiff(diff.diff);
      const beforeUri = vscode.Uri.from({
        scheme: DiffContentProvider.scheme,
        path: `/${contextId}/before/${diff.title}`,
      });
      const afterUri = vscode.Uri.from({
        scheme: DiffContentProvider.scheme,
        path: `/${contextId}/after/${diff.title}`,
      });
      this.diffProvider.setContent(beforeUri, before);
      this.diffProvider.setContent(afterUri, after);

      const label = `${diff.title} (+${diff.linesAdded} -${diff.linesDeleted})`;
      await vscode.commands.executeCommand("vscode.diff", beforeUri, afterUri, label);
    }
  }

  private async handleChatMessage(msg: { type: string; [key: string]: unknown }) {
    if (!this.client) {
      this.sendToWebview("error", { message: "Executor not connected" });
      return;
    }

    try {
      switch (msg.type) {
        case "submit": {
          const task = msg.task as string;
          const plannerModel = msg.plannerModel as string;
          const codeModel = msg.codeModel as string | undefined;
          const mode = (msg.mode as string) || "LUTZ";
          const reasoningLevel = msg.reasoningLevel as string | undefined;
          const reasoningLevelCode = msg.reasoningLevelCode as string | undefined;

          const opts: Record<string, unknown> = {};
          if (mode === "LUTZ") {
            opts.codeModel = codeModel || plannerModel;
          } else if (mode === "CODE") {
            opts.codeModel = plannerModel;
          } else if (mode === "SEARCH") {
            opts.scanModel = plannerModel;
          }

          if (reasoningLevel) {
            opts.reasoningLevel = reasoningLevel;
          }
          if (mode === "CODE") {
            // In CODE mode the planner select IS the code model —
            // propagate planner reasoning so the backend uses it
            if (reasoningLevel) {
              opts.reasoningLevelCode = reasoningLevel;
            }
          } else if (reasoningLevelCode) {
            opts.reasoningLevelCode = reasoningLevelCode;
          }

          const result = await this.client.submitJob(
            task,
            mode,
            plannerModel,
            opts
          );

          this.currentJobId = result.jobId;
          this.sendToWebview("jobStarted", { jobId: result.jobId });

          this.streamManager?.startStreaming(result.jobId);
          break;
        }

        case "cancel":
          if (this.currentJobId) {
            await this.client.cancelJob(this.currentJobId);
            this.streamManager?.stopStreaming();
            this.sendToWebview("jobCancelled", {});
            this.currentJobId = null;
          }
          break;
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      this.sendToWebview("error", { message });
    }
  }

  private async handleDropOthers(keepId: string) {
    if (!this.client) return;
    const ctx = await this.client.getContext();
    const toDrop = ctx.fragments
      .filter((f) => f.id !== keepId)
      .map((f) => f.id);
    if (toDrop.length > 0) {
      await this.client.dropFragments(toDrop);
    }
  }

  // ── Event Processing (from EventStreamManager) ───────

  private processEvent(event: JobEvent) {
    switch (event.type) {
      case "LLM_TOKEN": {
        const data = event.data as LlmTokenData;
        if (data && typeof data.token === "string") {
          this.sendToWebview("token", {
            token: data.token,
            isReasoning: data.isReasoning,
            isNewMessage: data.isNewMessage,
            isTerminal: data.isTerminal,
          });
        }
        break;
      }

      case "NOTIFICATION": {
        const data = event.data as NotificationData;
        if (data && data.level !== "COST") {
          this.log?.(`[NOTIFICATION ${data.level}] ${data.message}`);
          this.sendToWebview("notification", {
            level: data.level,
            message: data.message,
          });
        }
        break;
      }

      case "ERROR": {
        const data = event.data as ErrorData;
        if (data) {
          this.log?.(`[ERROR] ${data.title}: ${data.message}`);
          this.sendToWebview("error", {
            message: data.message,
            title: data.title,
          });
        }
        break;
      }

      case "CONFIRM_REQUEST": {
        const data = event.data as ConfirmRequestData;
        if (data) {
          this.sendToWebview("notification", {
            level: "INFO",
            message: `Auto-confirmed: ${data.title}`,
          });
        }
        break;
      }

      case "CONTEXT_BASELINE": {
        const data = event.data as ContextBaselineData;
        if (data) {
          this.sendToWebview("contextBaseline", {
            count: data.count,
            snippet: data.snippet,
          });
          this.refreshActivity();
        }
        break;
      }

      case "COMMAND_RESULT": {
        const data = event.data as CommandResultData;
        if (data) {
          this.sendToWebview("commandResult", {
            stage: data.stage,
            command: data.command,
            attempt: data.attempt,
            skipped: data.skipped,
            skipReason: data.skipReason,
            success: data.success,
            output: data.output,
            outputTruncated: data.outputTruncated,
            exception: data.exception,
          });
        }
        break;
      }

      case "STATE_HINT": {
        const data = event.data as StateHintData;
        if (data) {
          this.sendToWebview("stateHint", {
            name: data.name,
            value: data.value,
            details: data.details,
            count: data.count,
          });
        }
        break;
      }
    }
  }

  // ── File Actions ─────────────────────────────────────

  async addFilesByUri(uris: vscode.Uri[]) {
    if (!this.client) {
      vscode.window.showErrorMessage("Brokk: Executor not connected");
      return;
    }
    try {
      const paths = uris.map((u) => vscode.workspace.asRelativePath(u));
      await this.client.addFiles(paths);
      this.refreshContext();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`Brokk: ${message}`);
    }
  }

  async promptAddFile() {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders) return;

    const qp = vscode.window.createQuickPick<vscode.QuickPickItem & { uri: vscode.Uri }>();
    qp.placeholder = "Type to search files... (Space to select, OK to confirm)";
    qp.canSelectMany = true;
    qp.matchOnDescription = true;

    let searchTimer: ReturnType<typeof setTimeout> | null = null;

    qp.onDidChangeValue((value) => {
      if (searchTimer) clearTimeout(searchTimer);
      if (!value) {
        qp.items = [];
        return;
      }
      searchTimer = setTimeout(async () => {
        qp.busy = true;
        try {
          // Build case-insensitive glob: "Foo" → "[fF][oO][oO]"
          const ciValue = value.split("").map((c) => {
            if (/[a-zA-Z]/.test(c)) return `[${c.toLowerCase()}${c.toUpperCase()}]`;
            return c;
          }).join("");
          const glob = `**/*${ciValue}*`;
          const excludes = "{**/node_modules/**,**/.brokk/**,**/build/**,**/dist/**,**/out/**,**/.gradle/**,**/*.class}";
          const files = await vscode.workspace.findFiles(glob, excludes, 100);
          qp.items = files.map((f) => ({
            label: vscode.workspace.asRelativePath(f),
            uri: f,
          }));
        } catch {
          // ignore search errors
        }
        qp.busy = false;
      }, 200);
    });

    const result = await new Promise<(vscode.QuickPickItem & { uri: vscode.Uri })[]>((resolve) => {
      qp.onDidAccept(() => {
        if (qp.selectedItems.length > 0) {
          resolve([...qp.selectedItems]);
          qp.dispose();
        }
        // Nothing selected — ignore Enter
      });
      qp.onDidHide(() => {
        resolve([]);
        qp.dispose();
      });
      qp.show();
    });

    if (result.length > 0 && this.client) {
      const paths = result.map((p) => p.label);
      await this.client.addFiles(paths);
      this.refreshContext();
    }
  }

  // ── Attach Context Menu ───────────────────────────────

  private async showAttachContextMenu() {
    const pick = await vscode.window.showQuickPick(
      [
        { label: "$(file) Files", description: "Add files to context", value: "files" },
        { label: "$(symbol-class) Classes", description: "Add class summaries", value: "classes" },
        { label: "$(symbol-method) Methods", description: "Add method sources", value: "methods" },
        { label: "$(note) Text", description: "Paste text into context", value: "text" },
      ],
      { placeHolder: "Attach context..." }
    );
    if (!pick) return;

    switch (pick.value) {
      case "files":
        await this.promptAddFile();
        break;
      case "classes":
        await this.promptAddClass();
        break;
      case "methods":
        await this.promptAddMethod();
        break;
      case "text":
        await this.promptAddText();
        break;
    }
  }

  private async promptAddClass() {
    await this.promptAddSymbol(
      "Type to search classes... (Space to select, OK to confirm)",
      "class",
      (names) => this.client!.addClasses(names)
    );
  }

  private async promptAddMethod() {
    await this.promptAddSymbol(
      "Type to search methods... (Space to select, OK to confirm)",
      "function",
      (names) => this.client!.addMethods(names)
    );
  }

  private async promptAddSymbol(
    placeholder: string,
    typeFilter: string,
    addFn: (names: string[]) => Promise<unknown>
  ) {
    if (!this.client) return;
    const client = this.client;

    const qp = vscode.window.createQuickPick<vscode.QuickPickItem & { fqName: string }>();
    qp.placeholder = placeholder;
    qp.canSelectMany = true;
    qp.matchOnDescription = true;

    let searchTimer: ReturnType<typeof setTimeout> | null = null;

    qp.onDidChangeValue((value) => {
      if (searchTimer) clearTimeout(searchTimer);
      if (!value || value.length < 2) {
        qp.items = [];
        return;
      }
      searchTimer = setTimeout(async () => {
        qp.busy = true;
        try {
          const results = await client.getCompletions(value, 30);
          qp.items = results.completions
            .filter((c) => c.type === typeFilter)
            .map((c) => ({
              label: c.name,
              description: c.detail,
              fqName: c.detail,
            }));
        } catch {
          // ignore search errors
        }
        qp.busy = false;
      }, 200);
    });

    const result = await new Promise<(vscode.QuickPickItem & { fqName: string })[]>((resolve) => {
      qp.onDidAccept(() => {
        if (qp.selectedItems.length > 0) {
          resolve([...qp.selectedItems]);
          qp.dispose();
        }
      });
      qp.onDidHide(() => {
        resolve([]);
        qp.dispose();
      });
      qp.show();
    });

    if (result.length > 0) {
      try {
        await addFn(result.map((r) => r.fqName));
        this.refreshContext();
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : String(err);
        vscode.window.showErrorMessage(`Brokk: ${message}`);
      }
    }
  }

  private async promptAddText() {
    if (!this.client) return;
    const input = await vscode.window.showInputBox({
      placeHolder: "Paste text here...",
      prompt: "Enter text to add to context",
    });
    if (!input) return;
    try {
      await this.client.addText(input);
      this.refreshContext();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`Brokk: ${message}`);
    }
  }

  // ── Session Switching ───────────────────────────────

  private async promptSwitchSession() {
    if (!this.client) return;

    const sessionList = await this.client.listSessions();
    const currentId = sessionList.currentSessionId;

    type SessionPickItem = vscode.QuickPickItem & { sessionId: string; isNew?: boolean };

    const items: SessionPickItem[] = [
      {
        label: "New Session",
        description: "",
        detail: "Create a new session",
        sessionId: "",
        isNew: true,
      },
      { label: "", kind: vscode.QuickPickItemKind.Separator, sessionId: "" },
      ...sessionList.sessions.map((s) => ({
        label: s.name,
        description: s.id === currentId ? "(current)" : "",
        detail: `Modified: ${new Date(s.modified).toLocaleString()}`,
        sessionId: s.id,
      })),
    ];

    const pick = await vscode.window.showQuickPick(items, {
      placeHolder: "Select a session to switch to",
    });

    if (!pick) return;

    try {
      if (pick.isNew) {
        await this.client.createSession("New Session");
      } else if (pick.sessionId === currentId) {
        return;
      } else {
        await this.client.switchSession(pick.sessionId);
      }

      // Replay conversation from the new/switched session
      try {
        const conversation = await this.client.getConversation();
        this.sendToWebview("replayConversation", {
          entries: conversation.entries,
        });
      } catch {
        this.sendToWebview("resetChat", { message: "Switched session" });
      }

      this.refreshContext();
      this.refreshTaskList();
      this.refreshActivity();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`Brokk: ${message}`);
    }
  }

  // ── Helpers ──────────────────────────────────────────

  private sendToWebview(type: string, data: Record<string, unknown>) {
    this.view?.webview.postMessage({ type, ...data });
  }
}
