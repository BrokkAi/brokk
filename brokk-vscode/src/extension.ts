import * as vscode from "vscode";
import { ChildProcess } from "child_process";
import path from "path";
import { findJar, spawnExecutor, waitForReady } from "./executor/lifecycle";
import { BrokkClient } from "./executor/client";
import { EventStreamManager } from "./executor/EventStreamManager";
import { EventDispatcher } from "./executor/EventDispatcher";
import { BrokkPanelProvider } from "./providers/BrokkPanelProvider";
import { DiffContentProvider } from "./providers/DiffContentProvider";

let executorProcess: ChildProcess | null = null;
let statusBarItem: vscode.StatusBarItem;
let panelProvider: BrokkPanelProvider;
let eventDispatcher: EventDispatcher | null = null;

const outputChannel = vscode.window.createOutputChannel("Brokk");

function log(msg: string) {
  const ts = new Date().toISOString();
  outputChannel.appendLine(`[${ts}] ${msg}`);
}

export async function activate(context: vscode.ExtensionContext) {
  log("Brokk extension activating...");

  // Create status bar item
  statusBarItem = vscode.window.createStatusBarItem(
    vscode.StatusBarAlignment.Left,
    100
  );
  statusBarItem.text = "$(circle-slash) Brokk: Not connected";
  statusBarItem.tooltip = "Click to start executor, or configure brokk.executorPort in settings";
  statusBarItem.command = "brokk.startExecutor";
  statusBarItem.show();
  context.subscriptions.push(statusBarItem);

  // Register diff content provider for virtual documents
  const diffProvider = new DiffContentProvider();
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(
      DiffContentProvider.scheme,
      diffProvider
    )
  );

  // Register unified webview provider
  panelProvider = new BrokkPanelProvider(context.extensionUri, diffProvider);

  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(
      BrokkPanelProvider.viewType,
      panelProvider
    )
  );

  // Register commands
  context.subscriptions.push(
    vscode.commands.registerCommand("brokk.startExecutor", () =>
      connectOrSpawn(context)
    ),
    vscode.commands.registerCommand("brokk.stopExecutor", stopExecutor),
    vscode.commands.registerCommand(
      "brokk.addFile",
      (clickedUri?: vscode.Uri, selectedUris?: vscode.Uri[]) => {
        const uris = selectedUris && selectedUris.length > 0
          ? selectedUris
          : clickedUri
            ? [clickedUri]
            : null;
        if (uris) {
          panelProvider.addFilesByUri(uris);
        } else {
          panelProvider.promptAddFile();
        }
      }
    )
  );

  // Auto-connect (non-blocking — don't fail activation)
  connectOrSpawn(context);
}

async function connectOrSpawn(context: vscode.ExtensionContext) {
  const config = vscode.workspace.getConfiguration("brokk");
  const configPort = config.get<number>("executorPort", 0);
  const configToken = config.get<string>("authToken", "");

  // Priority: settings > env vars > auto-spawn
  const extPort = configPort || parseInt(process.env.BROKK_EXECUTOR_PORT || "0", 10);
  const extToken = configToken || process.env.BROKK_AUTH_TOKEN || "";

  if (extPort > 0 && extToken) {
    // Connect to existing executor
    log(`Connecting to external executor on port ${extPort}`);
    statusBarItem.text = "$(sync~spin) Brokk: Connecting...";
    try {
      const client = new BrokkClient(extPort, extToken);
      await client.checkLive();
      setConnected(client, extPort, "external");
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      log(`Failed to connect to external executor: ${message}`);
      statusBarItem.text = "$(error) Brokk: Connection failed";
      statusBarItem.tooltip = message;
      vscode.window.showErrorMessage(`Brokk: Could not connect to executor on port ${extPort}: ${message}`);
    }
    return;
  }

  // Auto-spawn: find JAR relative to the extension's install location
  // During development the extension is at <brokk-repo>/vscode-brokk/
  const workspaceFolders = vscode.workspace.workspaceFolders;
  if (!workspaceFolders) {
    statusBarItem.text = "$(warning) Brokk: No workspace";
    statusBarItem.tooltip = "Open a folder to use Brokk";
    return;
  }

  const workspaceDir = workspaceFolders[0].uri.fsPath;
  const extensionDir = context.extensionUri.fsPath;
  const brokkRoot = path.dirname(extensionDir); // extension is at <repo>/vscode-brokk

  log(`Workspace: ${workspaceDir}`);
  log(`Extension dir: ${extensionDir}`);
  log(`Brokk repo root (for JAR): ${brokkRoot}`);

  statusBarItem.text = "$(sync~spin) Brokk: Starting...";

  try {
    // Look for JAR in the brokk repo (where the extension lives), not in the workspace
    let jarPath: string;
    try {
      jarPath = await findJar(brokkRoot);
    } catch {
      // If we're actually inside the brokk repo as workspace, try there too
      jarPath = await findJar(workspaceDir);
    }
    log(`Found JAR: ${jarPath}`);

    const handle = await spawnExecutor(workspaceDir, jarPath);
    executorProcess = handle.process;
    log(`Executor spawned on port ${handle.port}`);

    statusBarItem.text = "$(sync~spin) Brokk: Waiting for ready...";
    const sessionId = await waitForReady(handle.port, handle.authToken);
    log(`Session ready: ${sessionId}`);

    const client = new BrokkClient(handle.port, handle.authToken);
    setConnected(client, handle.port, sessionId);

    context.subscriptions.push({
      dispose: () => {
        executorProcess?.kill();
      },
    });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    log(`Startup failed: ${message}`);
    statusBarItem.text = "$(error) Brokk: Failed";
    statusBarItem.tooltip = `${message}\n\nClick to retry, or set brokk.executorPort and brokk.authToken in settings.`;
    vscode.window.showErrorMessage(`Brokk: ${message}`);
  }
}

function setConnected(client: BrokkClient, port: number, sessionOrLabel: string) {
  // Create event infrastructure
  const streamManager = new EventStreamManager(client, log);
  const dispatcher = new EventDispatcher(streamManager, log);

  // Clean up previous dispatcher if reconnecting
  eventDispatcher?.dispose();
  eventDispatcher = dispatcher;

  // Wire up provider
  panelProvider.setLog(log);
  panelProvider.setClient(client);
  panelProvider.setStreamManager(streamManager);

  // Event-driven context refresh via STATE_HINT events
  dispatcher.onStateHint("workspaceUpdated", () => panelProvider.refreshContext());
  dispatcher.onStateHint("contextHistoryUpdated", () => panelProvider.refreshContext());
  dispatcher.onStateHint("gitRepoUpdated", () => panelProvider.refreshContext());

  // Task list updates arrive as taskInProgress STATE_HINT events
  dispatcher.onStateHint("taskInProgress", () => panelProvider.refreshTaskList());

  // CONTEXT_BASELINE events fire during jobs when context changes — use as refresh trigger.
  // refreshContext/refreshTaskList are already debounced (250ms), so just call them directly.
  dispatcher.on("CONTEXT_BASELINE", () => {
    panelProvider.refreshContext();
    panelProvider.refreshTaskList();
  });

  // Refresh context and task list when a job finishes (final state)
  streamManager.onJobFinished(() => {
    panelProvider.refreshContext();
    panelProvider.refreshTaskList();
  });

  statusBarItem.text = "$(check) Brokk: Connected";
  statusBarItem.tooltip = `Executor on port ${port}, session: ${sessionOrLabel}`;
  log(`Connected to executor on port ${port}`);
}

function stopExecutor() {
  if (executorProcess) {
    executorProcess.kill();
    executorProcess = null;
  }
  eventDispatcher?.dispose();
  eventDispatcher = null;
  statusBarItem.text = "$(circle-slash) Brokk: Stopped";
  statusBarItem.tooltip = "Click to restart";
  vscode.window.showInformationMessage("Brokk executor stopped.");
}

export function deactivate() {
  executorProcess?.kill();
  eventDispatcher?.dispose();
}
