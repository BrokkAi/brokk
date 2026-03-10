import * as fs from "fs";
import * as path from "path";
import * as os from "os";

const BROKK_SERVICE_URL = "https://app.brokk.ai";

/**
 * Returns the path to `brokk.properties` in the platform-appropriate config directory.
 * macOS:   ~/Library/Application Support/Brokk/brokk.properties
 * Linux:   $XDG_CONFIG_HOME/Brokk/brokk.properties  (fallback: ~/.config/Brokk/)
 * Windows: %APPDATA%/Brokk/brokk.properties
 */
export function getBrokkPropertiesPath(): string {
  const platform = process.platform;
  let configDir: string;
  if (platform === "darwin") {
    configDir = path.join(os.homedir(), "Library", "Application Support", "Brokk");
  } else if (platform === "win32") {
    const appData = process.env.APPDATA || path.join(os.homedir(), "AppData", "Roaming");
    configDir = path.join(appData, "Brokk");
  } else {
    const xdg = process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config");
    configDir = path.join(xdg, "Brokk");
  }
  return path.join(configDir, "brokk.properties");
}

/**
 * Read a value from brokk.properties (Java properties format).
 */
export function readProperty(key: string): string {
  const propsPath = getBrokkPropertiesPath();
  if (!fs.existsSync(propsPath)) return "";
  const content = fs.readFileSync(propsPath, "utf-8");
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed.startsWith("#") || trimmed.startsWith("!") || !trimmed) continue;
    const match = trimmed.match(/^([^=:\s]+)\s*[=:\s]\s*(.*)/);
    if (match && match[1] === key) {
      return match[2].replace(/\\:/g, ":").replace(/\\=/g, "=").replace(/\\\\/g, "\\");
    }
  }
  return "";
}

/**
 * Write a value to brokk.properties, preserving other entries.
 */
export function writeProperty(key: string, value: string): void {
  const propsPath = getBrokkPropertiesPath();
  const dir = path.dirname(propsPath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  let lines: string[] = [];
  if (fs.existsSync(propsPath)) {
    lines = fs.readFileSync(propsPath, "utf-8").split(/\r?\n/);
  }

  const escaped = value.replace(/\\/g, "\\\\").replace(/:/g, "\\:").replace(/=/g, "\\=");

  let found = false;
  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim();
    if (trimmed.startsWith("#") || trimmed.startsWith("!") || !trimmed) continue;
    const match = trimmed.match(/^([^=:\s]+)\s*[=:\s]/);
    if (match && match[1] === key) {
      if (value) {
        lines[i] = `${key}=${escaped}`;
      } else {
        lines.splice(i, 1);
      }
      found = true;
      break;
    }
  }

  if (!found && value) {
    lines.push(`${key}=${escaped}`);
  }

  fs.writeFileSync(propsPath, lines.join("\n"), "utf-8");
}

/**
 * Fetch the account balance from the Brokk API.
 */
export async function fetchBalanceFromApi(key: string): Promise<number> {
  const url = `${BROKK_SERVICE_URL}/api/payments/balance-lookup/${encodeURIComponent(key)}`;
  const resp = await fetch(url);
  if (resp.status === 401) {
    throw new Error("Invalid Brokk Key (unauthorized)");
  }
  if (!resp.ok) {
    throw new Error(`Failed to fetch balance (HTTP ${resp.status})`);
  }
  const data = await resp.json();
  if (typeof data === "number") return data;
  if (data && typeof data.available_balance === "number") return data.available_balance;
  throw new Error("Unexpected balance response format");
}

/**
 * Handle OpenAI-related webview messages.
 * @param openExternalUrl Callback to open a URL in the user's browser (injected from the panel provider)
 */
export async function handleOpenAiSettingsMessage(
  msg: { type: string; [key: string]: unknown },
  client: { startOpenAiOAuth: () => Promise<{ status: string; url?: string }>; getOpenAiOAuthStatus: () => Promise<{ connected: boolean }> },
  sendFn: (type: string, data: Record<string, unknown>) => void,
  openExternalUrl: (url: string) => void
): Promise<void> {
  try {
    switch (msg.type) {
      case "checkOpenAiStatus":
      case "pollOpenAiStatus": {
        const result = await client.getOpenAiOAuthStatus();
        sendFn("openAiStatusResult", { connected: result.connected });
        break;
      }

      case "connectOpenAi": {
        const result = await client.startOpenAiOAuth();
        if (result.url) {
          openExternalUrl(result.url);
          sendFn("openAiConnectStarted", { status: result.status });
        } else {
          sendFn("openAiStatusResult", { connected: false, error: "No authorization URL returned" });
        }
        break;
      }
    }
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    sendFn("openAiStatusResult", { connected: false, error: message });
  }
}

/**
 * Handle all settings-related webview messages.
 * @param sendFn  Function to send a message back to the webview (replaces this.sendToWebview)
 */
export async function handleSettingsMessage(
  msg: { type: string; [key: string]: unknown },
  sendFn: (type: string, data: Record<string, unknown>) => void
): Promise<void> {
  try {
    switch (msg.type) {
      case "loadSettings": {
        const key = readProperty("brokkApiKey");
        sendFn("settingsLoaded", { apiKey: key });
        break;
      }

      case "saveApiKey": {
        const key = ((msg.apiKey as string) ?? "").trim();
        if (key) {
          const parts = key.split("+");
          if (parts.length !== 3 || parts[0] !== "brk") {
            sendFn("settingsError", {
              message: "Invalid key format. Expected: brk+<userId>+<token>",
            });
            return;
          }
          try {
            const balance = await fetchBalanceFromApi(key);
            writeProperty("brokkApiKey", key);
            sendFn("settingsSaved", { balance });
          } catch (err: unknown) {
            const message = err instanceof Error ? err.message : String(err);
            sendFn("settingsError", { message });
            return;
          }
        } else {
          writeProperty("brokkApiKey", "");
          sendFn("settingsSaved", { balance: null });
        }
        break;
      }

      case "fetchBalance": {
        const key = readProperty("brokkApiKey");
        if (!key) {
          sendFn("balanceResult", { balance: null });
          return;
        }
        try {
          const balance = await fetchBalanceFromApi(key);
          sendFn("balanceResult", { balance });
        } catch (err: unknown) {
          const message = err instanceof Error ? err.message : String(err);
          sendFn("settingsError", { message });
        }
        break;
      }
    }
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    sendFn("settingsError", { message });
  }
}
