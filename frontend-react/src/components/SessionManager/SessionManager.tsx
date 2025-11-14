import { useCallback, useState } from "react";
import type { Session } from "../../types";

type Props = {
  session: Session | null;
  onCreate: (session: Session) => void;
  disabled?: boolean;
};

export function SessionManager({ session, onCreate, disabled }: Props) {
  const [name, setName] = useState<string>("");

  const create = useCallback(() => {
    const trimmed = name.trim();
    if (!trimmed) return;
    // Placeholder session creation; replace with API call
    const fakeSession: Session = {
      sessionId: crypto.randomUUID(),
      name: trimmed
    };
    onCreate(fakeSession);
    setName("");
  }, [name, onCreate]);

  return (
    <div className="stack">
      <div className="title">Session</div>
      {session ? (
        <div className="stack">
          <div style={{ color: "#9ca3af" }}>
            Active: {session.name}
          </div>
        </div>
      ) : (
        <div className="stack">
          <label>
            <div style={{ marginBottom: 4 }}>Name</div>
            <input
              className="input"
              placeholder="session-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={disabled}
            />
          </label>
          <button className="btn" onClick={create} disabled={disabled || !name.trim()}>
            Create Session
          </button>
        </div>
      )}
    </div>
  );
}
