import React from "react";
import { render } from "ink";
import { BrokkApp, type BrokkAppOptions } from "./BrokkApp.js";

export async function runInteractiveApp(options: BrokkAppOptions): Promise<void> {
  const useAltScreen = !!process.stdout.isTTY;
  if (useAltScreen) {
    process.stdout.write("\u001b[?1049h");
  }

  const instance = render(<BrokkApp {...options} />);
  try {
    await instance.waitUntilExit();
  } finally {
    if (useAltScreen) {
      process.stdout.write("\u001b[?1049l");
    }
  }
}
