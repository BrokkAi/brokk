import * as vscode from "vscode";
import { ChildProcess, spawn } from "child_process";
import { createInterface } from "readline";
import { randomUUID } from "crypto";
import { execSync } from "child_process";
import { existsSync, readdirSync, statSync } from "fs";
import os from "os";
import path from "path";

export type LaunchMode = "auto" | "jbang" | "local" | "external";

export interface ExecutorHandle {
  port: number;
  authToken: string;
  process: ChildProcess | null;
}

function pickPreferredJbangPath(candidates: string[]): string | null {
  if (candidates.length === 0) return null;
  if (process.platform !== "win32") return candidates[0];

  const cleaned = candidates.map((value) => value.trim()).filter(Boolean);
  const preferredOrder = [".exe", ".cmd", ".bat", "", ".ps1"];

  for (const ext of preferredOrder) {
    const match = cleaned.find((candidate) => {
      const lower = candidate.toLowerCase();
      if (ext === "") {
        return !lower.endsWith(".exe")
          && !lower.endsWith(".cmd")
          && !lower.endsWith(".bat")
          && !lower.endsWith(".ps1");
      }
      return lower.endsWith(ext);
    });
    if (match) return match;
  }

  return cleaned[0];
}

function spawnCommandOnWindows(command: string, args: string[], cwd: string): ChildProcess {
  const lower = command.toLowerCase();
  if (lower.endsWith(".ps1")) {
    return spawn(
      "powershell.exe",
      ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", command, ...args],
      { stdio: ["pipe", "pipe", "pipe"], cwd }
    );
  }

  if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
    return spawn(command, args, {
      stdio: ["pipe", "pipe", "pipe"],
      cwd,
      shell: true,
    });
  }

  return spawn(command, args, { stdio: ["pipe", "pipe", "pipe"], cwd });
}

/**
 * Find the newest brokk JAR in app/build/libs/, filtering out -sources JARs.
 * If explicitJar is provided and exists, use it directly.
 */
export async function findJar(repoRoot: string, explicitJar?: string): Promise<string> {
  if (explicitJar) {
    if (existsSync(explicitJar)) return explicitJar;
    throw new Error(`Configured JAR not found: ${explicitJar}`);
  }

  const libsDir = path.join(repoRoot, "app", "build", "libs");

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
 * Resolve a Java binary by checking common locations in order:
 * $JAVA_HOME/bin/java → ~/.gradle/jdks/ (highest version) → ~/.jbang/currentjdk/bin/java → bare "java"
 */
export function resolveJavaBinary(): string {
  // 1. $JAVA_HOME
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const candidate = path.join(javaHome, "bin", "java");
    if (existsSync(candidate)) return candidate;
  }

  // 2. ~/.gradle/jdks/ — pick highest version directory
  const gradleJdks = path.join(os.homedir(), ".gradle", "jdks");
  try {
    const dirs = readdirSync(gradleJdks)
      .filter((d) => {
        try {
          return statSync(path.join(gradleJdks, d)).isDirectory();
        } catch {
          return false;
        }
      })
      .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    for (const dir of dirs) {
      const candidate = path.join(gradleJdks, dir, "bin", "java");
      if (existsSync(candidate)) return candidate;
    }
  } catch {
    // directory doesn't exist
  }

  // 3. ~/.jbang/currentjdk/bin/java
  const jbangJdk = path.join(os.homedir(), ".jbang", "currentjdk", "bin", "java");
  if (existsSync(jbangJdk)) return jbangJdk;

  // 4. Bare "java" on PATH
  return "java";
}

/**
 * Spawn the headless executor Java process.
 * Returns the port, auth token, and process handle.
 */
export async function spawnExecutor(
  workspaceDir: string,
  jarPath: string
): Promise<ExecutorHandle> {
  const authToken = randomUUID();
  const execId = randomUUID();

  const child = spawn(
    resolveJavaBinary(),
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
 * Detect whether we're running inside the brokk repo (local mode)
 * or as a packaged extension (jbang mode).
 */
export function detectLaunchMode(extensionDir: string): "local" | "jbang" {
  const repoRoot = path.dirname(extensionDir);
  const libsDir = path.join(repoRoot, "app", "build", "libs");
  return existsSync(libsDir) ? "local" : "jbang";
}

/**
 * Check common locations for the jbang binary.
 * Returns the full path if found, null otherwise.
 */
export function resolveJbangBinary(): string | null {
  const isWindows = process.platform === "win32";

  // Check PATH first
  try {
    const which = isWindows ? "where" : "which";
    const result = execSync(`${which} jbang`, { stdio: "pipe" }).toString().trim();
    if (result) {
      const firstMatch = pickPreferredJbangPath(result.split(/\r?\n/));
      if (firstMatch) return firstMatch;
    }
  } catch {
    // not on PATH
  }

  // Check default install locations
  const home = os.homedir();
  const candidates: string[] = [];

  if (isWindows) {
    // On Windows, prioritize .cmd or .ps1 in the standard location
    candidates.push(
      path.join(home, ".jbang", "bin", "jbang.cmd"),
      path.join(home, ".jbang", "bin", "jbang.ps1"),
      path.join(home, ".jbang", "bin", "jbang")
    );
  } else {
    candidates.push(
      path.join(home, ".jbang", "bin", "jbang"),
      "/opt/homebrew/bin/jbang",
      "/usr/local/bin/jbang"
    );
  }

  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }

  return null;
}

/**
 * Install jbang via the official install script.
 * Returns the path to the installed binary.
 */
export async function installJbang(): Promise<string> {
  const isWindows = process.platform === "win32";

  const INSTALL_TIMEOUT_MS = 120_000; // 2 minutes

  const child = isWindows
    ? spawn("powershell", ["-Command", `iex "& { $(iwr -useb https://ps.jbang.dev) } app setup"`], { stdio: "pipe" })
    : spawn("bash", ["-c", "curl -Ls https://sh.jbang.dev | bash -s - app setup"], { stdio: "pipe" });

  await new Promise<void>((resolve, reject) => {
    let stderr = "";
    const timeout = setTimeout(() => {
      child.kill();
      reject(new Error("jbang installation timed out after 2 minutes"));
    }, INSTALL_TIMEOUT_MS);

    // Drain both stdout and stderr to prevent pipe buffer from filling
    child.stdout?.on("data", () => {});
    child.stderr?.on("data", (data: Buffer) => { stderr += data.toString(); });
    child.on("error", (err) => {
      clearTimeout(timeout);
      reject(new Error(`Failed to run jbang installer: ${err.message}`));
    });
    child.on("exit", (code) => {
      clearTimeout(timeout);
      if (code === 0) resolve();
      else reject(new Error(`jbang installer exited with code ${code}${stderr ? `: ${stderr.trim()}` : ""}`));
    });
  });

  // Trust the brokk catalog
  const jbangPath = resolveJbangBinary();
  if (!jbangPath) {
    throw new Error("jbang was installed but could not be found. You may need to restart VS Code.");
  }

  const trustUrls = [
    "https://github.com/BrokkAi/brokk-releases",
    "https://github.com/BrokkAi/brokk-releases/releases/download/",
  ];

  for (const url of trustUrls) {
    try {
      execSync(`"${jbangPath}" trust add ${url}`, { stdio: "pipe" });
    } catch {
      // Best-effort trust; don't fail installation if trust fails
    }
  }

  return jbangPath;
}

/**
 * Spawn the executor via jbang.
 * If jbangBinary is provided, use it; otherwise look up jbang on PATH.
 */
export async function spawnJbang(workspaceDir: string, jbangBinary?: string): Promise<ExecutorHandle> {
  const jbang = jbangBinary ?? "jbang";

  const authToken = randomUUID();
  const execId = randomUUID();

  const version = "0.23.1.beta6";
  const jarUrl = `https://github.com/BrokkAi/brokk-releases/releases/download/${version}/brokk-${version}.jar`;

  const jbangArgs = [
    "--java", "21",
    "--runtime-option=-Djava.awt.headless=true",
    "--runtime-option=-Dapple.awt.UIElement=true",
    "--runtime-option=--enable-native-access=ALL-UNNAMED",
    "--main", "ai.brokk.executor.HeadlessExecutorMain",
    jarUrl,
    "--listen-addr",
    "127.0.0.1:0",
    "--auth-token",
    authToken,
    "--workspace-dir",
    workspaceDir,
    "--exec-id",
    execId,
  ];

  const child = process.platform === "win32"
    ? spawnCommandOnWindows(jbang, jbangArgs, workspaceDir)
    : spawn(jbang, jbangArgs, { stdio: ["pipe", "pipe", "pipe"], cwd: workspaceDir });

  const port = await waitForPort(child);
  return { port, authToken, process: child };
}

/**
 * Parse the port from the executor's stdout output.
 * Looks for "Executor listening on http://127.0.0.1:<port>"
 */
function waitForPort(child: ChildProcess): Promise<number> {
  return new Promise((resolve, reject) => {
    const stderrChunks: string[] = [];

    const timeout = setTimeout(() => {
      const stderr = stderrChunks.join("").trim();
      reject(new Error(
        "Timed out waiting for executor to report its port"
        + (stderr ? `\n\nExecutor stderr:\n${stderr.slice(-2000)}` : "")
      ));
    }, 120_000);

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
      const spawnErr = err as NodeJS.ErrnoException;
      const details = [
        `message=${spawnErr.message}`,
        spawnErr.code ? `code=${spawnErr.code}` : "",
        spawnErr.errno ? `errno=${String(spawnErr.errno)}` : "",
        spawnErr.syscall ? `syscall=${spawnErr.syscall}` : "",
        spawnErr.path ? `path=${spawnErr.path}` : "",
        Array.isArray(spawnErr.spawnargs) ? `spawnargs=${JSON.stringify(spawnErr.spawnargs)}` : "",
      ].filter(Boolean).join(", ");
      reject(new Error(`Executor process error: ${details}`));
    });

    child.on("exit", (code) => {
      clearTimeout(timeout);
      const stderr = stderrChunks.join("").trim();
      reject(new Error(
        `Executor exited with code ${code} before reporting port`
        + (stderr ? `\n\nExecutor stderr:\n${stderr.slice(-2000)}` : "")
      ));
    });

    child.stderr?.on("data", (data: Buffer) => {
      const msg = data.toString();
      stderrChunks.push(msg);
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
