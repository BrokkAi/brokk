import { jsx as _jsx } from "react/jsx-runtime";
import { render } from "ink";
import { BrokkApp } from "./BrokkApp.js";
export async function runInteractiveApp(options) {
    const useAltScreen = !!process.stdout.isTTY;
    if (useAltScreen) {
        process.stdout.write("\u001b[?1049h");
    }
    const instance = render(_jsx(BrokkApp, { ...options }));
    try {
        await instance.waitUntilExit();
    }
    finally {
        if (useAltScreen) {
            process.stdout.write("\u001b[?1049l");
        }
    }
}
