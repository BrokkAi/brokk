import { useCallback, useMemo, useState } from "react";
import { SessionManager } from "./components/SessionManager";
import { PromptPanel } from "./components/PromptPanel";
import { OutputPanel } from "./components/OutputPanel";
import { MergeControls } from "./components/MergeControls";
import type { JobEvent, Session } from "./types";

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [events, setEvents] = useState<JobEvent[]>([]);
  const [busy, setBusy] = useState<boolean>(false);

  const onCreateSession = useCallback((s: Session) => {
    setSession(s);
    setEvents([]);
  }, []);

  const onSubmitPrompt = useCallback(async (prompt: string) => {
    // Placeholder behavior: append a fake event locally
    setBusy(true);
    const baseSeq = events.length ? events[events.length - 1].seq + 1 : 1;
    const now = new Date().toISOString();

    const newEvents: JobEvent[] = [
      { seq: baseSeq, type: "info", message: `Submitting prompt...`, timestamp: now },
      { seq: baseSeq + 1, type: "user", message: prompt, timestamp: now },
      { seq: baseSeq + 2, type: "assistant", message: "This is a placeholder response.", timestamp: now }
    ];
    setEvents(prev => [...prev, ...newEvents]);
    setBusy(false);
  }, [events]);

  const onMerge = useCallback(async () => {
    // Placeholder merge behavior
    const baseSeq = events.length ? events[events.length - 1].seq + 1 : 1;
    setEvents(prev => [
      ...prev,
      { seq: baseSeq, type: "info", message: "Merging worktree into main (placeholder)." }
    ]);
  }, [events.length]);

  const onDiscard = useCallback(async () => {
    // Placeholder discard behavior
    const baseSeq = events.length ? events[events.length - 1].seq + 1 : 1;
    setEvents(prev => [
      ...prev,
      { seq: baseSeq, type: "warn", message: "Discarded worktree (placeholder)." }
    ]);
  }, [events.length]);

  const sessionId = session?.sessionId ?? null;

  const sessionStatus = useMemo(() => {
    if (!session) return "No session";
    return `Session ${session.name} (${session.sessionId})`;
  }, [session]);

  return (
    <div className="container">
      <div className="app-grid">
        <header className="panel header">
          <div className="title">Brokk React UI</div>
          <div className="hstack">
            <span style={{ color: "#9ca3af" }}>{sessionStatus}</span>
          </div>
        </header>

        <aside className="panel">
          <SessionManager
            session={session}
            onCreate={onCreateSession}
            disabled={busy}
          />
        </aside>

        <main className="panel">
          <PromptPanel
            disabled={!sessionId || busy}
            onSubmit={onSubmitPrompt}
          />
        </main>

        <section className="panel">
          <OutputPanel events={events} />
        </section>

        <footer className="panel footer">
          <MergeControls
            disabled={!sessionId || busy}
            onMerge={onMerge}
            onDiscard={onDiscard}
          />
          <span style={{ marginLeft: "auto" }}>
            {busy ? "Working..." : "Idle"}
          </span>
        </footer>
      </div>
    </div>
  );
}
