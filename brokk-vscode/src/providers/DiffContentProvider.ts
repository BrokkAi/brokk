import * as vscode from "vscode";

/**
 * Virtual document provider for showing diffs in VS Code's built-in diff editor.
 * Stores before/after file content keyed by URI, then vscode.diff opens them
 * in a native side-by-side diff view.
 */
export class DiffContentProvider implements vscode.TextDocumentContentProvider {
  static readonly scheme = "brokk-diff";

  private content = new Map<string, string>();

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this.content.get(uri.toString()) || "";
  }

  setContent(uri: vscode.Uri, text: string) {
    this.content.set(uri.toString(), text);
  }

  clear() {
    this.content.clear();
  }
}

/**
 * Parse a unified diff into separate before/after text content.
 * Strips diff headers (---, +++, @@) and splits +/- lines appropriately.
 */
export function parseUnifiedDiff(diffText: string): { before: string; after: string } {
  const lines = diffText.split("\n");
  const beforeLines: string[] = [];
  const afterLines: string[] = [];

  for (const line of lines) {
    // Skip file headers and hunk headers
    if (line.startsWith("---") || line.startsWith("+++") || line.startsWith("@@")) {
      continue;
    }
    if (line.startsWith("-")) {
      beforeLines.push(line.substring(1));
    } else if (line.startsWith("+")) {
      afterLines.push(line.substring(1));
    } else if (line.startsWith(" ")) {
      // Context line — present in both
      beforeLines.push(line.substring(1));
      afterLines.push(line.substring(1));
    } else {
      // No prefix (e.g. empty line at end of diff)
      beforeLines.push(line);
      afterLines.push(line);
    }
  }

  return {
    before: beforeLines.join("\n"),
    after: afterLines.join("\n"),
  };
}
