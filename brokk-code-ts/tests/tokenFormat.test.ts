import { describe, expect, test } from "vitest";
import { formatTokenCount } from "../src/tokenFormat.js";

describe("formatTokenCount", () => {
  test("formats short and long numbers", () => {
    expect(formatTokenCount(999)).toBe("999");
    expect(formatTokenCount(1_250)).toBe("1.3k");
    expect(formatTokenCount(12_000)).toBe("12k");
    expect(formatTokenCount(1_500_000)).toBe("1.5m");
    expect(formatTokenCount(-2_000_000_000)).toBe("-2b");
  });
});
