import { createInterface } from "node:readline/promises";
import { randomUUID } from "node:crypto";
import { stdin, stdout } from "node:process";
import { homedir } from "node:os";
import { join } from "node:path";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { ExecutorManager, ExecutorError } from "../executor/ExecutorManager.js";
import { formatTokenCount } from "../tokenFormat.js";

export const VALID_MODES = new Set(["LUTZ", "ASK", "CODE"]);
export const MODE_OPTIONS = ["LUTZ", "CODE", "ASK"] as const;
export const BASE_MODEL_IDS = ["gpt-5.2", "gemini-3-flash-preview"] as const;
export const REASONING_LEVEL_IDS = ["low", "medium", "high", "disable", "default"] as const;
export const DEFAULT_MODEL_SELECTION = "gpt-5.2";
export const DEFAULT_REASONING_LEVEL = "low";

type JsonRecord = Record<string, unknown>;

export interface AcpDefaults {
  default_model: string;
  default_reasoning: string;
}

function acpSettingsFile(): string {
  return join(homedir(), ".brokk", "acp_settings.json");
}

export async function loadAcpDefaults(): Promise<AcpDefaults> {
  try {
    const raw = await readFile(acpSettingsFile(), "utf-8");
    const parsed = JSON.parse(raw) as Partial<AcpDefaults>;
    const model = String(parsed.default_model ?? DEFAULT_MODEL_SELECTION).trim() || DEFAULT_MODEL_SELECTION;
    const reasoning = String(parsed.default_reasoning ?? DEFAULT_REASONING_LEVEL).trim();
    return {
      default_model: model,
      default_reasoning: REASONING_LEVEL_IDS.includes(reasoning as (typeof REASONING_LEVEL_IDS)[number])
        ? reasoning
        : DEFAULT_REASONING_LEVEL
    };
  } catch {
    return {
      default_model: DEFAULT_MODEL_SELECTION,
      default_reasoning: DEFAULT_REASONING_LEVEL
    };
  }
}

export async function saveAcpDefaults(defaults: AcpDefaults): Promise<void> {
  const path = acpSettingsFile();
  const tmp = `${path}.tmp`;
  await mkdir(join(homedir(), ".brokk"), { recursive: true });
  await writeFile(tmp, `${JSON.stringify(defaults, null, 2)}\n`, "utf-8");
  await rename(tmp, path);
}

export function normalizeMode(mode?: string): string {
  if (!mode) {
    return "LUTZ";
  }
  const upper = mode.trim().toUpperCase();
  return VALID_MODES.has(upper) ? upper : "LUTZ";
}

export function resolveModelSelection(modelSelection?: string): [string, string | undefined] {
  const raw = (modelSelection ?? "").trim();
  if (!raw) {
    return [DEFAULT_MODEL_SELECTION, undefined];
  }
  if (!raw.includes("#r=")) {
    return [raw, undefined];
  }
  const [modelId, reasoning] = raw.split("#r=", 2);
  const normalizedReasoning = reasoning.trim().toLowerCase();
  if (!REASONING_LEVEL_IDS.includes(normalizedReasoning as (typeof REASONING_LEVEL_IDS)[number])) {
    return [modelId.trim() || DEFAULT_MODEL_SELECTION, undefined];
  }
  return [modelId.trim() || DEFAULT_MODEL_SELECTION, normalizedReasoning];
}

export function normalizeModelCatalog(payload: JsonRecord): JsonRecord[] {
  const models = payload.models;
  if (!Array.isArray(models)) {
    return [];
  }

  const seen = new Set<string>();
  const normalized: JsonRecord[] = [];
  for (const model of models) {
    if (!model || typeof model !== "object") {
      continue;
    }
    const rec = model as JsonRecord;
    const name = typeof rec.name === "string" ? rec.name.trim() : "";
    if (!name || seen.has(name)) {
      continue;
    }
    seen.add(name);
    normalized.push({
      name,
      location: typeof rec.location === "string" ? rec.location : name,
      supportsReasoningEffort: Boolean(rec.supportsReasoningEffort),
      supportsReasoningDisable: Boolean(rec.supportsReasoningDisable)
    });
  }
  return normalized;
}

export function modelVariantsForModel(modelName: string, catalog: JsonRecord[]): string[] {
  const entry = catalog.find((m) => m.name === modelName);
  if (!entry) {
    return [];
  }
  if (!entry.supportsReasoningEffort) {
    return [];
  }
  const variants = ["low", "medium", "high"];
  if (entry.supportsReasoningDisable) {
    variants.push("disable");
  }
  return variants;
}

export function buildAvailableModels(catalog: JsonRecord[]): Array<[string, string]> {
  const available: Array<[string, string]> = [];
  for (const model of catalog) {
    if (typeof model.name !== "string") {
      continue;
    }
    available.push([model.name, model.name]);
    for (const variant of modelVariantsForModel(model.name, catalog)) {
      available.push([`${model.name}/${variant}`, `${model.name} (${variant})`]);
    }
  }
  return available;
}

export function parseModelSelection(
  selection: string,
  catalog: JsonRecord[]
): [string | undefined, string | undefined] {
  let raw = (selection ?? "").trim();
  if (!raw) {
    return [undefined, undefined];
  }
  if (raw.startsWith("model/")) {
    raw = raw.slice("model/".length).trim();
  }
  if (raw.startsWith("reasoning/")) {
    const level = raw.slice("reasoning/".length).trim().toLowerCase();
    return [undefined, REASONING_LEVEL_IDS.includes(level as (typeof REASONING_LEVEL_IDS)[number]) ? level : undefined];
  }

  const availableModels = new Set(catalog.map((m) => m.name).filter((n): n is string => typeof n === "string"));
  if (availableModels.has(raw)) {
    return [raw, undefined];
  }

  if (raw.includes("/")) {
    const segments = raw.split("/");
    const candidateVariant = segments.pop()?.trim().toLowerCase();
    const baseModel = segments.join("/").trim();
    if (candidateVariant && availableModels.has(baseModel)) {
      if (modelVariantsForModel(baseModel, catalog).includes(candidateVariant)) {
        return [baseModel, candidateVariant];
      }
    }
  }

  return [raw, undefined];
}

export function extractPromptText(prompt: unknown): string {
  if (typeof prompt === "string") {
    return prompt.trim();
  }
  if (!Array.isArray(prompt)) {
    return "";
  }
  const parts: string[] = [];
  for (const block of prompt) {
    if (!block || typeof block !== "object") {
      continue;
    }
    const rec = block as JsonRecord;
    if (rec.type === "text" && typeof rec.text === "string") {
      const stripped = rec.text.trim();
      if (stripped) {
        parts.push(stripped);
      }
    }
  }
  return parts.join("\n").trim();
}

export function mapExecutorEventToSessionUpdate(event: JsonRecord): JsonRecord | undefined {
  const eventType = event.type;
  const data = (event.data && typeof event.data === "object" ? event.data : {}) as JsonRecord;

  if (eventType === "LLM_TOKEN") {
    const token = data.token;
    if (typeof token !== "string" || !token) {
      return undefined;
    }
    return { sessionUpdate: "agent_message_chunk", text: token };
  }

  if (eventType === "ERROR") {
    const message = typeof data.message === "string" ? data.message : "Unknown error";
    return { sessionUpdate: "agent_message_chunk", text: `\n[ERROR] ${message}\n` };
  }

  if (eventType === "NOTIFICATION") {
    const level = typeof data.level === "string" ? data.level : "INFO";
    const message = typeof data.message === "string" ? data.message : "";
    if (!message) {
      return undefined;
    }
    return { sessionUpdate: "agent_thought_chunk", text: `[${level}] ${message}` };
  }

  if (eventType === "STATE_HINT") {
    const message = typeof data.message === "string" ? data.message.trim() : "";
    if (!message) {
      return undefined;
    }
    return { sessionUpdate: "agent_thought_chunk", text: message };
  }

  return undefined;
}

function formatChip(fragment: JsonRecord): string {
  const chipKind = String(fragment.chip_kind ?? fragment.chipKind ?? "OTHER");
  const description = String(fragment.shortDescription ?? "Unknown");
  let text = `${chipKind} ${description}`;
  const tokens = typeof fragment.tokens === "number" ? fragment.tokens : 0;
  if (tokens > 0) {
    text += ` ${formatTokenCount(tokens)}t`;
  }
  if (fragment.pinned) {
    text += " [PIN]";
  }
  return text;
}

export function buildContextChipBlocks(contextData: JsonRecord): JsonRecord[] {
  const fragments = Array.isArray(contextData.fragments)
    ? contextData.fragments.filter((f): f is JsonRecord => !!f && typeof f === "object")
    : [];
  return fragments.map((fragment) => ({
    type: "text",
    text: formatChip(fragment)
  }));
}

interface AcpSessionState {
  brokkSessionId: string;
  jobId?: string;
  mode: string;
  plannerModel: string;
  reasoning: string;
}

export async function runAcpServer(options: {
  workspaceDir: string;
  jarPath?: string;
  executorVersion?: string;
  executorSnapshot?: boolean;
  vendor?: string;
  ide?: string;
}): Promise<void> {
  const executor = new ExecutorManager({
    workspaceDir: options.workspaceDir,
    jarPath: options.jarPath,
    executorVersion: options.executorVersion,
    executorSnapshot: options.executorSnapshot,
    vendor: options.vendor,
    exitOnStdinEof: true
  });

  await executor.start();
  const ready = await executor.waitReady();
  if (!ready) {
    throw new ExecutorError("Executor failed readiness in ACP mode");
  }

  const defaults = await loadAcpDefaults();
  const sessions = new Map<string, AcpSessionState>();
  const rl = createInterface({ input: stdin, crlfDelay: Infinity });

  const writeMessage = (msg: JsonRecord): void => {
    stdout.write(`${JSON.stringify(msg)}\n`);
  };

  try {
    for await (const line of rl) {
      if (!line.trim()) {
        continue;
      }
      let msg: JsonRecord;
      try {
        msg = JSON.parse(line) as JsonRecord;
      } catch {
        writeMessage({ error: "Invalid JSON payload" });
        continue;
      }

      const method = msg.method;
      const id = msg.id;
      const params = (msg.params && typeof msg.params === "object" ? msg.params : {}) as JsonRecord;

      if (method === "shutdown") {
        writeMessage({ id, result: { ok: true } });
        break;
      }

      if (method === "prompt") {
        const acpSessionId = typeof params.sessionId === "string" ? params.sessionId : randomUUID();
        let state = sessions.get(acpSessionId);
        if (!state) {
          const brokkSessionId = await executor.createSession("ACP Session");
          state = {
            brokkSessionId,
            mode: normalizeMode(typeof params.mode === "string" ? params.mode : undefined),
            plannerModel: defaults.default_model,
            reasoning: defaults.default_reasoning
          };
          sessions.set(acpSessionId, state);
        }

        const promptText = extractPromptText(params.prompt);
        if (!promptText) {
          writeMessage({ id, error: "Prompt text is empty" });
          continue;
        }

        const modelPayload = await executor.getModels().catch(() => ({ models: [] }));
        const catalog = normalizeModelCatalog(modelPayload);
        const [selectedModel, selectedVariant] = parseModelSelection(
          typeof params.model === "string" ? params.model : state.plannerModel,
          catalog.length > 0 ? catalog : [{ name: DEFAULT_MODEL_SELECTION }]
        );

        if (selectedModel) {
          state.plannerModel = selectedModel;
        }
        if (selectedVariant) {
          state.reasoning = selectedVariant;
        }

        const jobId = await executor.submitJob({
          taskInput: promptText,
          plannerModel: state.plannerModel,
          reasoningLevel: state.reasoning,
          mode: state.mode,
          sessionId: state.brokkSessionId
        });
        state.jobId = jobId;

        writeMessage({ id, result: { accepted: true, sessionId: acpSessionId, jobId } });
        for await (const event of executor.streamEvents(jobId)) {
          const update = mapExecutorEventToSessionUpdate(event);
          if (update) {
            writeMessage({ method: "session_update", params: { sessionId: acpSessionId, ...update } });
          }
        }
        const context = await executor.getContext().catch(() => ({ fragments: [] }));
        const chips = buildContextChipBlocks(context);
        if (chips.length > 0) {
          writeMessage({
            method: "session_update",
            params: {
              sessionId: acpSessionId,
              sessionUpdate: "context_snapshot",
              content: chips
            }
          });
        }
        continue;
      }

      if (method === "cancel") {
        const acpSessionId = typeof params.sessionId === "string" ? params.sessionId : "";
        const state = sessions.get(acpSessionId);
        if (!state?.jobId) {
          writeMessage({ id, result: { cancelled: false } });
          continue;
        }
        await executor.cancelJob(state.jobId);
        state.jobId = undefined;
        writeMessage({ id, result: { cancelled: true } });
        continue;
      }

      writeMessage({ id, error: `Unsupported method: ${String(method)}` });
    }
  } finally {
    rl.close();
    await executor.stop();
  }
}
