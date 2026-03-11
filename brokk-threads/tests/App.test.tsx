import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";

describe("App", () => {
  function baseBridge(overrides: Partial<typeof window.brokkThreads> = {}) {
    return {
      getInitialShellState: vi.fn().mockResolvedValue({
        threads: [],
        selectedThreadId: null
      }),
      createThread: vi.fn(),
      renameThread: vi.fn(),
      selectThread: vi.fn(),
      ensureThreadProvisionedForPrompt: vi.fn(),
      sendPrompt: vi.fn().mockResolvedValue(undefined),
      subscribeOutput: vi.fn().mockResolvedValue(undefined),
      debugActiveExecutors: vi.fn().mockResolvedValue([]),
      ...overrides
    };
  }
  it("renders three-pane shell and hydrates thread list from preload bridge", async () => {
    const startExecutor = vi.fn();
    const createWorktree = vi.fn();

    window.brokkThreads = baseBridge({
      getInitialShellState: vi.fn().mockResolvedValue({
        threads: [
          {
            id: "a",
            title: "Alpha Thread",
            createdAt: "2026-01-01T00:00:00.000Z",
            updatedAt: "2026-01-01T00:00:00.000Z"
          },
          {
            id: "b",
            title: "Beta Thread",
            createdAt: "2026-01-02T00:00:00.000Z",
            updatedAt: "2026-01-02T00:00:00.000Z"
          }
        ],
        selectedThreadId: "b"
      })
    });

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
    window.brokkThreads = baseBridge({
      getInitialShellState: vi.fn().mockResolvedValue({
        threads: [],
        selectedThreadId: null
      })
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText("No thread selected")).toBeInTheDocument();
    });
  });

  it("renders unprovisioned and provisioned thread states distinctly", async () => {
    window.brokkThreads = baseBridge({
      getInitialShellState: vi.fn().mockResolvedValue({
        threads: [
          {
            id: "u",
            title: "Unprovisioned Thread",
            createdAt: "2026-01-01T00:00:00.000Z",
            updatedAt: "2026-01-01T00:00:00.000Z"
          },
          {
            id: "p",
            title: "Provisioned Thread",
            createdAt: "2026-01-02T00:00:00.000Z",
            updatedAt: "2026-01-02T00:00:00.000Z",
            provisioning: {
              branch: "feature/p",
              worktreePath: "/tmp/wt1",
              brokkSessionId: "session-1",
              executor: {
                executorId: "exec-1",
                startedAt: "2026-01-02T00:00:00.000Z"
              }
            }
          }
        ],
        selectedThreadId: "u"
      })
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText("Unprovisioned Thread")).toBeInTheDocument();
      expect(screen.getByText("Provisioned Thread")).toBeInTheDocument();
      expect(screen.getByText("Not provisioned")).toBeInTheDocument();
      expect(screen.getByText("Provisioned")).toBeInTheDocument();
    });
  });

  it("sending first prompt provisions lazily and does not provision on select-only", async () => {
    const getInitialShellState = vi
      .fn()
      .mockResolvedValueOnce({
        threads: [
          {
            id: "u",
            title: "Unprovisioned Thread",
            createdAt: "2026-01-01T00:00:00.000Z",
            updatedAt: "2026-01-01T00:00:00.000Z"
          },
          {
            id: "v",
            title: "Other Thread",
            createdAt: "2026-01-01T00:00:00.000Z",
            updatedAt: "2026-01-01T00:00:00.000Z"
          }
        ],
        selectedThreadId: "u"
      })
      .mockResolvedValueOnce({
        threads: [
          {
            id: "u",
            title: "Unprovisioned Thread",
            createdAt: "2026-01-01T00:00:00.000Z",
            updatedAt: "2026-01-01T00:00:01.000Z",
            provisioning: {
              branch: "feature/u",
              worktreePath: "/tmp/wt-u",
              brokkSessionId: "session-u",
              executor: {
                executorId: "exec-u",
                startedAt: "2026-01-01T00:00:01.000Z"
              }
            }
          },
          {
            id: "v",
            title: "Other Thread",
            createdAt: "2026-01-01T00:00:00.000Z",
            updatedAt: "2026-01-01T00:00:00.000Z"
          }
        ],
        selectedThreadId: "u"
      });

    const sendPrompt = vi.fn().mockResolvedValue(undefined);
    const selectThread = vi.fn().mockResolvedValue(undefined);

    window.brokkThreads = baseBridge({
      getInitialShellState,
      sendPrompt,
      selectThread
    });

    render(<App />);

    await waitFor(() => expect(screen.getByText("Unprovisioned Thread")).toBeInTheDocument());

    fireEvent.click(screen.getByText("Other Thread"));
    expect(sendPrompt).not.toHaveBeenCalled();

    fireEvent.change(screen.getByTestId("prompt-input"), { target: { value: "hello lazy provision" } });
    fireEvent.click(screen.getByTestId("send-prompt-button"));

    await waitFor(() => expect(sendPrompt).toHaveBeenCalledWith("u", "hello lazy provision"));
    await waitFor(() => expect(screen.getByText("Provisioned")).toBeInTheDocument());
  });

  it("surfaces send prompt failures", async () => {
    window.brokkThreads = baseBridge({
      getInitialShellState: vi.fn().mockResolvedValue({
        threads: [
          {
            id: "u",
            title: "Unprovisioned Thread",
            createdAt: "2026-01-01T00:00:00.000Z",
            updatedAt: "2026-01-01T00:00:00.000Z"
          }
        ],
        selectedThreadId: "u"
      }),
      sendPrompt: vi.fn().mockRejectedValue(new Error("provision failed"))
    });

    render(<App />);

    await waitFor(() => expect(screen.getByText("Unprovisioned Thread")).toBeInTheDocument());

    fireEvent.change(screen.getByTestId("prompt-input"), { target: { value: "hello" } });
    fireEvent.click(screen.getByTestId("send-prompt-button"));

    await waitFor(() => expect(screen.getByTestId("prompt-error")).toHaveTextContent("provision failed"));
  });
});
