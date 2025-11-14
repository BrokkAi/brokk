import type { JobEvent } from "../../types";

type Props = {
  events: JobEvent[];
};

function EventRow({ evt }: { evt: JobEvent }) {
  const color =
    evt.type === "error" ? "#ef4444" :
    evt.type === "warn" ? "#f59e0b" :
    evt.type === "user" ? "#93c5fd" :
    evt.type === "assistant" ? "#34d399" :
    "#9ca3af";

  return (
    <div style={{ borderBottom: "1px solid #1f2937", padding: "6px 0" }}>
      <div style={{ fontSize: 12, color }}>
        [{evt.seq}] {evt.type}
        {evt.timestamp ? ` • ${new Date(evt.timestamp).toLocaleTimeString()}` : null}
      </div>
      <div style={{ whiteSpace: "pre-wrap" }}>{evt.message}</div>
    </div>
  );
}

export function OutputPanel({ events }: Props) {
  return (
    <div className="stack">
      <div className="title">Output</div>
      <div style={{ overflow: "auto", maxHeight: "50vh" }}>
        {events.length === 0 ? (
          <div style={{ color: "#9ca3af" }}>No output yet.</div>
        ) : (
          events.map((e) => <EventRow key={e.seq} evt={e} />)
        )}
      </div>
    </div>
  );
}
