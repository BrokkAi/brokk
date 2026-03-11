import { useEffect, useMemo, useState } from "react";
import type { InitialShellState, ThreadMetadata } from "../electron/types";

const emptyState: InitialShellState = { threads: [], selectedThreadId: null };

export function App() {
  const [state, setState] = useState<InitialShellState>(emptyState);

  useEffect(() => {
    let active = true;
    window.brokkThreads.getInitialShellState().then((nextState) => {
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

  return (
    <div className="app-shell" data-testid="app-shell">
      <header className="top-pane" data-testid="top-pane">
        <h1>Brokk Threads</h1>
        <textarea placeholder="Prompt..." />
      </header>
      <div className="main-panes">
        <aside className="left-pane" data-testid="left-pane">
          <h2>Threads</h2>
          <ul>
            {state.threads.map((thread) => (
              <li key={thread.id}>{thread.title}</li>
            ))}
          </ul>
        </aside>
        <section className="right-pane" data-testid="right-pane">
          <h2>Output</h2>
          <div>{selectedThread ? selectedThread.title : "No thread selected"}</div>
        </section>
      </div>
    </div>
  );
}
