import * as vscode from "vscode";
import { ChildProcess, spawn } from "child_process";
import { createInterface } from "readline";
import { randomUUID } from "crypto";
import { readdirSync, statSync } from "fs";
import path from "path";

export interface ExecutorHandle {
  port: number;
  authToken: string;
  process: ChildProcess | null;
}

/**
 * Find the newest brokk JAR in app/build/libs/, filtering out -sources JARs.
 */
export async function findJar(workspaceRoot: string): Promise<string> {
  const libsDir = path.join(workspaceRoot, "app", "build", "libs");

  const matches: { path: string; mtime: number }[] = [];
  let entries: string[];
  try {
    entries = readdirSync(libsDir);
  } catch {
    entries = [];
  }
  for (const name of entries) {
    if (!name.startsWith("brokk-") || !name.endsWith(".jar")) continue;
    if (name.includes("-sources")) continue;
    const fullPath = path.join(libsDir, name);
    try {
      const stat = statSync(fullPath);
      matches.push({ path: fullPath, mtime: stat.mtimeMs });
    } catch {
      // skip files we can't stat
    }
  }

  if (matches.length === 0) {
    throw new Error(
      `No brokk JAR found in ${libsDir}. Run ./gradlew :app:shadowJar first.`
    );
  }

  matches.sort((a, b) => b.mtime - a.mtime);
  return matches[0].path;
}

/**
 * Spawn the headless executor Java process.
 * Returns the port, auth token, and process handle.
 */
export async function spawnExecutor(
  workspaceDir: string,
  jarPath: string
): Promise<ExecutorHandle> {
  // Check for external executor (for development)
  const envPort = process.env.BROKK_EXECUTOR_PORT;
  const envToken = process.env.BROKK_AUTH_TOKEN;
  if (envPort && envToken) {
    const port = parseInt(envPort, 10);
    if (isNaN(port)) throw new Error(`Invalid BROKK_EXECUTOR_PORT: ${envPort}`);
    return { port, authToken: envToken, process: null };
  }

  const authToken = randomUUID();
  const execId = randomUUID();

  const child = spawn(
    "java",
    [
      "-cp",
      jarPath,
      "ai.brokk.executor.HeadlessExecutorMain",
      "--listen-addr",
      "127.0.0.1:0",
      "--auth-token",
      authToken,
      "--workspace-dir",
      workspaceDir,
      "--exec-id",
      execId,
    ],
    {
      stdio: ["pipe", "pipe", "pipe"],
      cwd: workspaceDir,
    }
  );

  const port = await waitForPort(child);
  return { port, authToken, process: child };
}

/**
 * Parse the port from the executor's stdout output.
 * Looks for "Executor listening on http://127.0.0.1:<port>"
 */
function waitForPort(child: ChildProcess): Promise<number> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error("Timed out waiting for executor to report its port"));
    }, 60_000);

    if (!child.stdout) {
      clearTimeout(timeout);
      reject(new Error("No stdout on executor process"));
      return;
    }

    const rl = createInterface({ input: child.stdout });

    rl.on("line", (line) => {
      console.log(`[executor stdout] ${line}`);
      const port = extractPort(line);
      if (port !== null) {
        clearTimeout(timeout);
        rl.close();
        resolve(port);
      }
    });

    child.on("error", (err) => {
      clearTimeout(timeout);
      reject(new Error(`Executor process error: ${err.message}`));
    });

    child.on("exit", (code) => {
      clearTimeout(timeout);
      reject(new Error(`Executor exited with code ${code} before reporting port`));
    });

    // Pipe stderr to the output channel for debugging
    child.stderr?.on("data", (data: Buffer) => {
      const msg = data.toString();
      if (msg.trim()) {
        console.log(`[executor stderr] ${msg.trimEnd()}`);
      }
    });
  });
}

/**
 * Extract port from a line of output.
 * Matches "Executor listening on http://127.0.0.1:<port>"
 */
function extractPort(line: string): number | null {
  // Match URL pattern: http://host:port
  const urlMatch = line.match(/https?:\/\/[^:]+:(\d+)/);
  if (urlMatch) {
    const port = parseInt(urlMatch[1], 10);
    if (port > 0 && port <= 65535) return port;
  }
  return null;
}

/**
 * Poll /health/live, create a session via POST /v1/sessions, then poll /health/ready.
 * The sessionLoaded flag in the executor is only set by SessionsRouter,
 * so we must create a session before readiness will resolve.
 */
export async function waitForReady(
  port: number,
  authToken: string,
  cancelToken?: vscode.CancellationToken
): Promise<string> {
  const baseUrl = `http://127.0.0.1:${port}`;

  // Wait for liveness
  await poll(
    async () => {
      const res = await fetch(`${baseUrl}/health/live`);
      return res.ok;
    },
    500,
    30_000,
    cancelToken
  );

  // Create a session — this sets sessionLoaded=true in the executor
  await fetch(`${baseUrl}/v1/sessions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${authToken}`,
    },
    body: JSON.stringify({ name: "New Session" }),
  });

  // Wait for readiness
  let sessionId = "";
  await poll(
    async () => {
      const res = await fetch(`${baseUrl}/health/ready`);
      if (!res.ok) return false;
      const data = (await res.json()) as { status: string; sessionId: string };
      sessionId = data.sessionId;
      return true;
    },
    500,
    60_000,
    cancelToken
  );

  return sessionId;
}

async function poll(
  check: () => Promise<boolean>,
  intervalMs: number,
  timeoutMs: number,
  cancelToken?: vscode.CancellationToken
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (cancelToken?.isCancellationRequested) {
      throw new Error("Cancelled");
    }
    try {
      if (await check()) return;
    } catch {
      // keep polling
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error("Timed out waiting for executor");
}
