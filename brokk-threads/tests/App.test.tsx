import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";

describe("App", () => {
  it("renders three-pane shell and hydrates thread list from preload bridge", async () => {
    const startExecutor = vi.fn();
    const createWorktree = vi.fn();

    window.brokkThreads = {
      getInitialShellState: vi.fn().mockResolvedValue({
        threads: [
          { id: "a", title: "Alpha Thread", updatedAt: "2026-01-01T00:00:00.000Z" },
          { id: "b", title: "Beta Thread", updatedAt: "2026-01-02T00:00:00.000Z" }
        ],
        selectedThreadId: "b"
      })
    };

    render(<App />);

    expect(screen.getByTestId("top-pane")).toBeInTheDocument();
    expect(screen.getByTestId("left-pane")).toBeInTheDocument();
    expect(screen.getByTestId("right-pane")).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText("Alpha Thread")).toBeInTheDocument();
      expect(screen.getByText("Beta Thread")).toBeInTheDocument();
    });

    expect(screen.getByText("Beta Thread")).toBeInTheDocument();
    expect(startExecutor).not.toHaveBeenCalled();
    expect(createWorktree).not.toHaveBeenCalled();
  });

  it("renders shell with zero threads", async () => {
    window.brokkThreads = {
      getInitialShellState: vi.fn().mockResolvedValue({
        threads: [],
        selectedThreadId: null
      })
    };

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText("No thread selected")).toBeInTheDocument();
    });
  });
});
