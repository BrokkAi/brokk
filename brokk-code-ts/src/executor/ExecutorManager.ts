import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { createGunzip } from "node:zlib";
import tar from "tar-stream";
import { resolveWorkspaceDir } from "../workspace.js";

export class ExecutorError extends Error {}

export interface ExecutorManagerOptions {
  workspaceDir?: string;
  jarPath?: string;
  executorVersion?: string;
  executorSnapshot?: boolean;
  vendor?: string;
  exitOnStdinEof?: boolean;
}

type JsonRecord = Record<string, unknown>;

export class ExecutorManager {
  private readonly workspaceDir: string;
  private readonly jarOverride?: string;
  private readonly executorVersion?: string;
  private readonly useSnapshot: boolean;
  private readonly vendor?: string;
  private readonly exitOnStdinEof: boolean;
  private process?: ChildProcessWithoutNullStreams;
  private authToken: string;
  private baseUrl?: string;
  public sessionId?: string;
  public resolvedJarPath?: string;

  public constructor(options: ExecutorManagerOptions = {}) {
    this.workspaceDir = resolveWorkspaceDir(options.workspaceDir ?? process.cwd());
    this.jarOverride = options.jarPath;
    this.executorVersion = options.executorVersion;
    this.useSnapshot = options.executorSnapshot ?? true;
    this.vendor = options.vendor;
    this.exitOnStdinEof = options.exitOnStdinEof ?? false;
    this.authToken = randomUUID();
  }

  private sanitizeTagForFilename(tag: string): string {
    const stripped = tag.trim();
    const sanitized = stripped.replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^[._-]+|[._-]+$/g, "");
    return sanitized || "unknown";
  }

  private cachedJarPath(version?: string): string {
    const destDir = join(homedir(), ".brokk");
    if (!version) {
      return join(destDir, this.useSnapshot ? "brokk-snapshot.jar" : "brokk.jar");
    }
    return join(destDir, `brokk-${this.sanitizeTagForFilename(version)}.jar`);
  }

  private async findJar(): Promise<string> {
    if (this.jarOverride) {
      const explicit = resolve(this.jarOverride);
      if (!existsSync(explicit)) {
        throw new ExecutorError(`Provided jar path does not exist: ${explicit}`);
      }
      return explicit;
    }

    const cached = this.cachedJarPath(this.executorVersion);
    if (existsSync(cached)) {
      return cached;
    }

    if (!this.executorVersion) {
      const direct = join(this.workspaceDir, "app", "build", "libs", "brokk.jar");
      if (existsSync(direct)) {
        return direct;
      }

      let current = this.workspaceDir;
      while (true) {
        if (existsSync(join(current, "gradlew"))) {
          const candidate = join(current, "app", "build", "libs", "brokk.jar");
          if (existsSync(candidate)) {
            return candidate;
          }
        }
        const parent = dirname(current);
        if (parent === current) {
          break;
        }
        current = parent;
      }
    }

    return await this.downloadJar(this.executorVersion);
  }

  private async fetchReleasePages(): Promise<JsonRecord[]> {
    const all: JsonRecord[] = [];
    for (let page = 1; page < 100; page += 1) {
      const url = new URL("https://api.github.com/repos/BrokkAi/brokk-releases/releases");
      url.searchParams.set("per_page", "100");
      url.searchParams.set("page", `${page}`);
      const response = await fetch(url, {
        headers: {
          "User-Agent": "brokk-code-ts"
        }
      });
      if (!response.ok) {
        throw new ExecutorError(`Failed to fetch release metadata: ${response.status}`);
      }
      const json = (await response.json()) as unknown;
      if (!Array.isArray(json) || json.length === 0) {
        break;
      }
      all.push(...(json.filter((item): item is JsonRecord => !!item && typeof item === "object")));
    }
    return all;
  }

  private selectRelease(releases: JsonRecord[], requested?: string): JsonRecord {
    if (requested) {
      const exact = releases.find((r) => r.tag_name === requested);
      if (exact) {
        return exact;
      }
      throw new ExecutorError(`Executor release tag not found: '${requested}'`);
    }

    const matcher = this.useSnapshot
      ? (tag: string) => tag.toLowerCase().includes("snapshot")
      : (tag: string) => !tag.toLowerCase().includes("snapshot");
    const found = releases.find((r) => {
      const tag = r.tag_name;
      return typeof tag === "string" && matcher(tag);
    });
    if (found) {
      return found;
    }
    if (this.useSnapshot && releases.length > 0) {
      return releases[0];
    }
    throw new ExecutorError(`No suitable ${this.useSnapshot ? "snapshot" : "stable"} release found`);
  }

  private async extractJarFromTgz(tgzBytes: Uint8Array, version?: string): Promise<Uint8Array> {
    const extract = tar.extract();
    const gunzip = createGunzip();

    return await new Promise<Uint8Array>((resolvePromise, rejectPromise) => {
      const candidates: Array<{ name: string; data: Uint8Array }> = [];
      extract.on("entry", (header, stream, next) => {
        if (!header.name.endsWith(".jar")) {
          stream.resume();
          stream.on("end", next);
          return;
        }
        const chunks: Buffer[] = [];
        stream.on("data", (chunk: Buffer) => chunks.push(chunk));
        stream.on("end", () => {
          candidates.push({ name: header.name, data: Buffer.concat(chunks) });
          next();
        });
      });

      extract.on("finish", () => {
        if (candidates.length === 0) {
          rejectPromise(new ExecutorError("No .jar files found in archive"));
          return;
        }

        if (version) {
          const exact = `package/jdeploy-bundle/brokk-${version}.jar`;
          const hit = candidates.find((c) => c.name === exact);
          if (hit) {
            resolvePromise(hit.data);
            return;
          }
        }

        const pathMatch = candidates.find(
          (c) => c.name.includes("jdeploy-bundle") && c.name.toLowerCase().includes("brokk")
        );
        if (pathMatch) {
          resolvePromise(pathMatch.data);
          return;
        }

        const nameMatch = candidates.find((c) => c.name.toLowerCase().includes("brokk"));
        if (nameMatch) {
          resolvePromise(nameMatch.data);
          return;
        }

        rejectPromise(new ExecutorError("Could not find a suitable Brokk JAR in archive"));
      });

      extract.on("error", rejectPromise);
      gunzip.on("error", rejectPromise);

      gunzip.pipe(extract);
      gunzip.end(Buffer.from(tgzBytes));
    });
  }

  private async downloadJar(version?: string): Promise<string> {
    const requested = version?.trim();
    const destJar = this.cachedJarPath(version);
    await mkdir(dirname(destJar), { recursive: true });

    const releases = await this.fetchReleasePages();
    const target = this.selectRelease(releases, requested);
    const assets = Array.isArray(target.assets)
      ? (target.assets.filter((a): a is JsonRecord => !!a && typeof a === "object"))
      : [];

    const jarAsset = assets.find((a) => typeof a.name === "string" && a.name.endsWith(".jar"));
    const archiveAssets = assets.filter(
      (a) => typeof a.name === "string" && (a.name.endsWith(".tgz") || a.name.endsWith(".tar.gz"))
    );

    if (jarAsset) {
      const url = jarAsset.browser_download_url;
      if (typeof url !== "string") {
        throw new ExecutorError("Invalid jar asset download URL");
      }
      const response = await fetch(url, { headers: { "User-Agent": "brokk-code-ts" } });
      if (!response.ok) {
        throw new ExecutorError(`Failed to download jar asset: ${response.status}`);
      }
      const bytes = new Uint8Array(await response.arrayBuffer());
      await writeFile(destJar, bytes);
      return destJar;
    }

    if (archiveAssets.length > 0) {
      let selected = archiveAssets[0];
      if (requested) {
        const expected = new Set([`brokk-${requested}.tgz`, `brokk-${requested}.tar.gz`]);
        const exact = archiveAssets.find((a) => typeof a.name === "string" && expected.has(a.name));
        if (exact) {
          selected = exact;
        }
      }

      const url = selected.browser_download_url;
      if (typeof url !== "string") {
        throw new ExecutorError("Invalid archive asset download URL");
      }
      const response = await fetch(url, { headers: { "User-Agent": "brokk-code-ts" } });
      if (!response.ok) {
        throw new ExecutorError(`Failed to download archive asset: ${response.status}`);
      }
      const jarBytes = await this.extractJarFromTgz(new Uint8Array(await response.arrayBuffer()), requested);
      await writeFile(destJar, jarBytes);
      return destJar;
    }

    throw new ExecutorError("Executor release has no .jar or .tgz asset");
  }

  public async start(): Promise<void> {
    const jarPath = await this.findJar();
    this.resolvedJarPath = jarPath;
    const execId = randomUUID();
    const args = [
      "-Djava.awt.headless=true",
      "-Dapple.awt.UIElement=true",
      "-cp",
      jarPath,
      "ai.brokk.executor.HeadlessExecutorMain",
      "--exec-id",
      execId,
      "--listen-addr",
      "127.0.0.1:0",
      "--auth-token",
      this.authToken,
      "--workspace-dir",
      this.workspaceDir
    ];

    if (this.vendor && this.vendor.trim()) {
      args.push("--vendor", this.vendor.trim());
    }
    if (this.exitOnStdinEof) {
      args.push("--exit-on-stdin-eof");
    }

    this.process = spawn("java", args, {
      stdio: ["pipe", "pipe", "pipe"]
    });

    const startDeadline = Date.now() + 10_000;
    const portRegex = /Executor listening on http:\/\/127\.0\.0\.1:(\d+)/;

    let output = "";
    const onData = (chunk: Buffer): void => {
      output += chunk.toString("utf-8");
    };

    this.process.stdout.on("data", onData);
    while (Date.now() < startDeadline) {
      const match = output.match(portRegex);
      if (match?.[1]) {
        this.baseUrl = `http://127.0.0.1:${match[1]}`;
        return;
      }
      if (this.process.exitCode !== null) {
        throw new ExecutorError(`Executor exited early with code ${this.process.exitCode}`);
      }
      await sleep(50);
    }

    await this.stop();
    throw new ExecutorError("Failed to extract port from executor output");
  }

  private ensureStarted(): string {
    if (!this.baseUrl) {
      throw new ExecutorError("Executor not started");
    }
    return this.baseUrl;
  }

  private async request(endpoint: string, options: RequestInit = {}, auth = true): Promise<Response> {
    const url = `${this.ensureStarted()}${endpoint}`;
    const headers = new Headers(options.headers ?? {});
    if (auth) {
      headers.set("Authorization", `Bearer ${this.authToken}`);
    }
    const response = await fetch(url, {
      ...options,
      headers
    });
    return response;
  }

  private async json(endpoint: string, options: RequestInit = {}): Promise<JsonRecord> {
    const response = await this.request(endpoint, options, true);
    if (!response.ok) {
      throw new ExecutorError(`${options.method ?? "GET"} ${endpoint} failed with ${response.status}`);
    }
    return (await response.json()) as JsonRecord;
  }

  public async getHealthLive(): Promise<JsonRecord> {
    const response = await this.request("/health/live", {}, false);
    if (!response.ok) {
      throw new ExecutorError(`/health/live failed with ${response.status}`);
    }
    return (await response.json()) as JsonRecord;
  }

  public async waitReady(timeout = 30): Promise<boolean> {
    const deadline = Date.now() + timeout * 1000;
    while (Date.now() < deadline) {
      const response = await this.request("/health/ready", {}, true).catch(() => undefined);
      if (response && response.status === 200) {
        return true;
      }
      await sleep(500);
    }
    return false;
  }

  public async createSession(name = "TUI Session"): Promise<string> {
    const data = await this.json("/v1/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name })
    });
    if (typeof data.sessionId !== "string") {
      throw new ExecutorError("Session create response missing sessionId");
    }
    this.sessionId = data.sessionId;
    return data.sessionId;
  }

  public async downloadSessionZip(sessionId: string): Promise<Uint8Array> {
    const response = await this.request(`/v1/sessions/${encodeURIComponent(sessionId)}`);
    if (!response.ok) {
      throw new ExecutorError(`Failed to download session ${sessionId}: ${response.status}`);
    }
    return new Uint8Array(await response.arrayBuffer());
  }

  public async importSessionZip(zipBytes: Uint8Array, sessionId?: string): Promise<string> {
    const headers: HeadersInit = {
      "Content-Type": "application/zip"
    };
    if (sessionId) {
      headers["X-Session-Id"] = sessionId;
    }
    const data = await this.json("/v1/sessions", {
      method: "PUT",
      headers,
      body: Buffer.from(zipBytes)
    });
    if (typeof data.sessionId !== "string") {
      throw new ExecutorError("Import response missing sessionId");
    }
    this.sessionId = data.sessionId;
    return data.sessionId;
  }

  public async submitJob(options: {
    taskInput: string;
    plannerModel: string;
    codeModel?: string;
    reasoningLevel?: string;
    reasoningLevelCode?: string;
    mode?: string;
    tags?: Record<string, string>;
    sessionId?: string;
    autoCommit?: boolean;
  }): Promise<string> {
    const tags = { ...(options.tags ?? {}) };
    if (!tags.mode) {
      tags.mode = options.mode ?? "LUTZ";
    }

    const payload: Record<string, unknown> = {
      taskInput: options.taskInput,
      plannerModel: options.plannerModel,
      autoCommit: options.autoCommit ?? true,
      autoCompress: true,
      tags
    };
    if (options.codeModel) {
      payload.codeModel = options.codeModel;
    }
    if (options.reasoningLevel) {
      payload.reasoningLevel = options.reasoningLevel;
    }
    if (options.reasoningLevelCode) {
      payload.reasoningLevelCode = options.reasoningLevelCode;
    }

    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      "Idempotency-Key": randomUUID()
    };
    const effectiveSession = options.sessionId ?? this.sessionId;
    if (effectiveSession) {
      headers["X-Session-Id"] = effectiveSession;
    }

    const data = await this.json("/v1/jobs", {
      method: "POST",
      headers,
      body: JSON.stringify(payload)
    });
    if (typeof data.jobId !== "string") {
      throw new ExecutorError("Job response missing jobId");
    }
    return data.jobId;
  }

  public async *streamEvents(jobId: string): AsyncGenerator<JsonRecord> {
    let afterSeq = -1;
    let state = "QUEUED";
    let currentSleepMs = 50;

    for (;;) {
      const status = await this.json(`/v1/jobs/${jobId}`);
      state = typeof status.state === "string" ? status.state : "QUEUED";

      const eventsData = await this.json(`/v1/jobs/${jobId}/events?after=${afterSeq}&limit=100`);
      const events = Array.isArray(eventsData.events)
        ? eventsData.events.filter((e): e is JsonRecord => !!e && typeof e === "object")
        : [];
      if (typeof eventsData.nextAfter === "number") {
        afterSeq = eventsData.nextAfter;
      }

      for (const event of events) {
        yield event;
      }

      if (["COMPLETED", "FAILED", "CANCELLED"].includes(state) && events.length === 0) {
        break;
      }

      if (events.length === 0) {
        await sleep(currentSleepMs);
        currentSleepMs = Math.min(500, currentSleepMs * 2);
      } else {
        currentSleepMs = 50;
        if (events.length < 100) {
          await sleep(50);
        }
      }
    }
  }

  public async getContext(): Promise<JsonRecord> {
    return await this.json("/v1/context?tokens=true");
  }

  public async getContextFragment(fragmentId: string): Promise<JsonRecord> {
    const encoded = encodeURIComponent(fragmentId);
    return await this.json(`/v1/context/fragments/${encoded}`);
  }

  public async getModels(): Promise<JsonRecord> {
    return await this.json("/v1/models");
  }

  public async dropContextFragments(fragmentIds: string[]): Promise<JsonRecord> {
    return await this.json("/v1/context/drop", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fragmentIds })
    });
  }

  public async setContextFragmentPinned(fragmentId: string, pinned: boolean): Promise<JsonRecord> {
    return await this.json("/v1/context/pin", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fragmentId, pinned })
    });
  }

  public async setContextFragmentReadonly(fragmentId: string, readonly: boolean): Promise<JsonRecord> {
    return await this.json("/v1/context/readonly", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fragmentId, readonly })
    });
  }

  public async compressContextHistory(): Promise<JsonRecord> {
    return await this.json("/v1/context/compress-history", { method: "POST" });
  }

  public async clearContextHistory(): Promise<JsonRecord> {
    return await this.json("/v1/context/clear-history", { method: "POST" });
  }

  public async dropAllContext(): Promise<JsonRecord> {
    return await this.json("/v1/context/drop-all", { method: "POST" });
  }

  public async getTasklist(): Promise<JsonRecord> {
    return await this.json("/v1/tasklist");
  }

  public async setTasklist(tasklistData: JsonRecord): Promise<JsonRecord> {
    return await this.json("/v1/tasklist", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(tasklistData)
    });
  }

  public async cancelJob(jobId: string): Promise<void> {
    await this.request(`/v1/jobs/${jobId}/cancel`, { method: "POST" }).catch(() => undefined);
  }

  public checkAlive(): boolean {
    return !!this.process && this.process.exitCode === null;
  }

  public async stop(): Promise<void> {
    if (!this.process) {
      return;
    }

    if (this.process.stdin.writable) {
      this.process.stdin.end();
    }

    this.process.kill("SIGTERM");
    const start = Date.now();
    while (this.process.exitCode === null && Date.now() - start < 3000) {
      await sleep(50);
    }
    if (this.process.exitCode === null) {
      this.process.kill("SIGKILL");
    }
    this.process = undefined;
    this.baseUrl = undefined;
    this.sessionId = undefined;
  }
}

export async function fileExists(path: string): Promise<boolean> {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}
