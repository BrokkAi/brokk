import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

export const DEFAULT_MAX_HISTORY = 100;

export function historyFile(workspaceDir: string): string {
  return join(workspaceDir, ".brokk", "prompts.json");
}

export async function loadHistory(workspaceDir: string): Promise<string[]> {
  try {
    const raw = await readFile(historyFile(workspaceDir), "utf-8");
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.map((item) => String(item));
  } catch {
    return [];
  }
}

export async function saveHistory(workspaceDir: string, history: string[]): Promise<void> {
  const file = historyFile(workspaceDir);
  const stateDir = join(workspaceDir, ".brokk");
  const tmp = `${file}.tmp`;
  await mkdir(stateDir, { recursive: true });
  await writeFile(tmp, `${JSON.stringify(history, null, 2)}\n`, "utf-8");
  await rename(tmp, file);
}

export async function appendPrompt(
  workspaceDir: string,
  prompt: string,
  maxHistory = DEFAULT_MAX_HISTORY
): Promise<void> {
  if (!prompt.trim()) {
    return;
  }
  const history = await loadHistory(workspaceDir);
  history.push(prompt);
  const bounded = history.length > maxHistory ? history.slice(history.length - maxHistory) : history;
  await saveHistory(workspaceDir, bounded);
}

export async function clearHistory(workspaceDir: string): Promise<void> {
  await rm(historyFile(workspaceDir), { force: true });
}
