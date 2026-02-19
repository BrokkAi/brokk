import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

export const DEFAULT_THEME = "textual-dark";
export const DEFAULT_PROMPT_HISTORY_SIZE = 50;

const LEGACY_THEME_ALIASES: Record<string, string> = {
  "builtin:dark": "textual-dark",
  "builtin:light": "textual-light",
  dark: "textual-dark",
  light: "textual-light",
  "brokk-dark": "textual-dark",
  "brokk-light": "textual-light"
};

export interface SettingsData {
  theme: string;
  prompt_history_size: number;
  last_model?: string;
  last_code_model?: string;
  last_reasoning_level?: string;
  last_code_reasoning_level?: string;
  last_auto_commit?: boolean;
}

export function normalizeThemeName(theme: string): string {
  return LEGACY_THEME_ALIASES[theme] ?? theme;
}

export function settingsDir(): string {
  return join(homedir(), ".brokk");
}

export function settingsFile(): string {
  return join(settingsDir(), "settings.json");
}

export function defaultSettings(): SettingsData {
  return {
    theme: DEFAULT_THEME,
    prompt_history_size: DEFAULT_PROMPT_HISTORY_SIZE
  };
}

export async function loadSettings(): Promise<SettingsData> {
  const path = settingsFile();
  try {
    const raw = await readFile(path, "utf-8");
    const parsed = JSON.parse(raw) as Partial<SettingsData>;
    const defaults = defaultSettings();
    const settings: SettingsData = {
      ...defaults,
      ...(parsed ?? {})
    };
    settings.theme = normalizeThemeName(settings.theme);
    return settings;
  } catch {
    return defaultSettings();
  }
}

export async function saveSettings(settings: SettingsData): Promise<void> {
  const path = settingsFile();
  const tmp = `${path}.tmp`;
  await mkdir(settingsDir(), { recursive: true });
  await writeFile(tmp, `${JSON.stringify(settings, null, 2)}\n`, "utf-8");
  await rename(tmp, path);
}
