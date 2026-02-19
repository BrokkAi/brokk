import { describe, expect, test } from "vitest";
import {
  formatFragmentStatus,
  formatStatusMetadata,
  normalizeWorkspaceDisplay
} from "../src/ui/statusFormatter.js";

describe("statusFormatter", () => {
  test("formats compact metadata line", () => {
    const line = formatStatusMetadata({
      mode: "LUTZ",
      model: "gpt-5.2",
      reasoning: "high",
      workspace: "/var/tmp/project",
      branch: "main"
    });
    expect(line).toBe("LUTZ • gpt-5.2 (high) • /var/tmp/project • main");
  });

  test("abbreviates home paths", () => {
    expect(normalizeWorkspaceDisplay("/home/user", "/home/user")).toBe("~");
    expect(normalizeWorkspaceDisplay("/home/user/projects/brokk", "/home/user")).toBe(
      "~/projects/brokk"
    );
  });

  test("normalizes windows slashes", () => {
    expect(normalizeWorkspaceDisplay("D:\\work\\repo", "C:\\Users\\user")).toBe("D:/work/repo");
  });

  test("formats fragment override", () => {
    expect(formatFragmentStatus("my-file.ts", 1234)).toBe("my-file.ts (1.2k tokens)");
  });
});
