import { describe, expect, test } from "vitest";
import {
  buildAvailableModels,
  extractPromptText,
  mapExecutorEventToSessionUpdate,
  normalizeMode,
  parseModelSelection,
  resolveModelSelection
} from "../src/acp/server.js";

describe("acp helpers", () => {
  test("normalizes mode", () => {
    expect(normalizeMode(undefined)).toBe("LUTZ");
    expect(normalizeMode("code")).toBe("CODE");
  });

  test("resolves model selection", () => {
    expect(resolveModelSelection("gpt-5.2#r=low")).toEqual(["gpt-5.2", "low"]);
    expect(resolveModelSelection("gpt-5.2")).toEqual(["gpt-5.2", undefined]);
  });

  test("builds model variants", () => {
    const models = [
      { name: "gpt-5.2", supportsReasoningEffort: true, supportsReasoningDisable: true },
      { name: "gemini", supportsReasoningEffort: false, supportsReasoningDisable: false }
    ];
    expect(buildAvailableModels(models)).toEqual([
      ["gpt-5.2", "gpt-5.2"],
      ["gpt-5.2/low", "gpt-5.2 (low)"],
      ["gpt-5.2/medium", "gpt-5.2 (medium)"],
      ["gpt-5.2/high", "gpt-5.2 (high)"],
      ["gpt-5.2/disable", "gpt-5.2 (disable)"],
      ["gemini", "gemini"]
    ]);
    expect(parseModelSelection("gpt-5.2/high", models)).toEqual(["gpt-5.2", "high"]);
  });

  test("extracts prompt text", () => {
    expect(
      extractPromptText([
        { type: "text", text: "Hello" },
        { type: "image", url: "http://example.com/x.png" },
        { type: "text", text: "World" }
      ])
    ).toBe("Hello\nWorld");
  });

  test("maps executor events", () => {
    expect(mapExecutorEventToSessionUpdate({ type: "LLM_TOKEN", data: { token: "abc" } })).toEqual({
      sessionUpdate: "agent_message_chunk",
      text: "abc"
    });
  });
});
