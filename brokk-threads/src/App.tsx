import { FormEvent, useEffect, useMemo, useState } from "react";
import type { InitialShellState, ThreadMetadata } from "../electron/types.js";

const emptyState: InitialShellState = { threads: [], selectedThreadId: null };

function isProvisioned(thread: ThreadMetadata): boolean {
  const provisioning = thread.provisioning;
  return Boolean(
    provisioning?.branch ||
      provisioning?.worktreePath ||
      provisioning?.brokkSessionId ||
      provisioning?.executor
  );
}

function getBridge(): typeof window.brokkThreads {
  if (typeof window !== "undefined" && window.brokkThreads) {
    return window.brokkThreads;
  }
  return {
    getInitialShellState: async () => ({ threads: [], selectedThreadId: null }),
    createThread: async () => ({} as any),
    renameThread: async () => ({} as any),
    selectThread: async () => {},
    ensureThreadProvisionedForPrompt: async () => ({ thread: {} as any, created: false }),
    sendPrompt: async () => {},
    subscribeOutput: async () => {},
    debugActiveExecutors: async () => []
  };
}

export function App() {
  const [state, setState] = useState<InitialShellState>(emptyState);
  const [prompt, setPrompt] = useState("");
  const [outputByThreadId, setOutputByThreadId] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getBridge()
      .getInitialShellState()
      .then((nextState) => {
        if (active) {
          setState(nextState);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  const selectedThread = useMemo<ThreadMetadata | null>(() => {
    return state.threads.find((thread) => thread.id === state.selectedThreadId) ?? null;
  }, [state.threads, state.selectedThreadId]);

  useEffect(() => {
    getBridge().subscribeOutput((payload) => {
      setOutputByThreadId((prev) => ({
        ...prev,
        [payload.threadId]: `${prev[payload.threadId] ?? ""}${prev[payload.threadId] ? "\n" : ""}${payload.text}`
      }));
    });
  }, []);

  async function onSubmitPrompt(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (!selectedThread || !prompt.trim()) {
      return;
    }

    const threadId = selectedThread.id;
    const promptToSend = prompt.trim();
    setPrompt("");

    try {
      const bridge = getBridge();
      await bridge.sendPrompt(threadId, promptToSend);
      // Refresh state to show "Provisioned" label if it was the first prompt
      const nextState = await bridge.getInitialShellState();
      setState(nextState);
    } catch (submitError) {
      setPrompt(promptToSend); // Restore prompt on error
      setError(submitError instanceof Error ? submitError.message : "Failed to send prompt");
    }
  }

  async function onSelectThread(threadId: string) {
    await getBridge().selectThread(threadId);
    setState((prev) => ({ ...prev, selectedThreadId: threadId }));
  }

  return (
    <div className="app-shell" data-testid="app-shell">
      <header className="top-pane" data-testid="top-pane">
        <h1>Brokk Threads</h1>
        <form onSubmit={onSubmitPrompt}>
          <textarea
            placeholder="Prompt..."
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            data-testid="prompt-input"
          />
          <button type="submit" data-testid="send-prompt-button">
            Send
          </button>
        </form>
        {error ? <div data-testid="prompt-error">{error}</div> : null}
      </header>
      <div className="main-panes">
        <aside className="left-pane" data-testid="left-pane">
          <h2>Threads</h2>
          <ul>
            {state.threads.map((thread) => (
              <li key={thread.id}>
                <button type="button" onClick={() => onSelectThread(thread.id)}>
                  {thread.title}
                </button>
                <small>{isProvisioned(thread) ? "Provisioned" : "Not provisioned"}</small>
              </li>
            ))}
          </ul>
        </aside>
        <section className="right-pane" data-testid="right-pane">
          <h2>Output</h2>
          <div>{selectedThread ? selectedThread.title : "No thread selected"}</div>
          <pre data-testid="thread-output">
            {selectedThread ? outputByThreadId[selectedThread.id] ?? "" : ""}
          </pre>
        </section>
      </div>
    </div>
  );
}
