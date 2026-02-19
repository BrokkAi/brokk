import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, test } from "vitest";
import { resolveWorkspaceDir } from "../src/workspace.js";

describe("resolveWorkspaceDir", () => {
  test("returns repo root when nested", () => {
    const root = mkdtempSync(join(tmpdir(), "brokk-ts-workspace-"));
    mkdirSync(join(root, ".git"));
    mkdirSync(join(root, "a", "b"), { recursive: true });
    const nested = join(root, "a", "b");
    expect(resolveWorkspaceDir(nested)).toBe(root);
  });

  test("returns input when outside git", () => {
    const root = mkdtempSync(join(tmpdir(), "brokk-ts-workspace-"));
    writeFileSync(join(root, "x.txt"), "ok");
    expect(resolveWorkspaceDir(join(root, "x.txt"))).toBe(join(root, "x.txt"));
  });
});
