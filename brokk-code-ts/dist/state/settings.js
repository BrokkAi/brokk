import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
export const DEFAULT_THEME = "textual-dark";
export const DEFAULT_PROMPT_HISTORY_SIZE = 50;
const LEGACY_THEME_ALIASES = {
    "builtin:dark": "textual-dark",
    "builtin:light": "textual-light",
    dark: "textual-dark",
    light: "textual-light",
    "brokk-dark": "textual-dark",
    "brokk-light": "textual-light"
};
export function normalizeThemeName(theme) {
    return LEGACY_THEME_ALIASES[theme] ?? theme;
}
export function settingsDir() {
    return join(homedir(), ".brokk");
}
export function settingsFile() {
    return join(settingsDir(), "settings.json");
}
export function defaultSettings() {
    return {
        theme: DEFAULT_THEME,
        prompt_history_size: DEFAULT_PROMPT_HISTORY_SIZE
    };
}
export async function loadSettings() {
    const path = settingsFile();
    try {
        const raw = await readFile(path, "utf-8");
        const parsed = JSON.parse(raw);
        const defaults = defaultSettings();
        const settings = {
            ...defaults,
            ...(parsed ?? {})
        };
        settings.theme = normalizeThemeName(settings.theme);
        return settings;
    }
    catch {
        return defaultSettings();
    }
}
export async function saveSettings(settings) {
    const path = settingsFile();
    const tmp = `${path}.tmp`;
    await mkdir(settingsDir(), { recursive: true });
    await writeFile(tmp, `${JSON.stringify(settings, null, 2)}\n`, "utf-8");
    await rename(tmp, path);
}
