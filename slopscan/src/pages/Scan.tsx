import { useState } from "react";

export function ScanPage() {
  const [repoUrl, setRepoUrl] = useState("");
  const [isScanning, setIsScanning] = useState(false);

  const handleStartScan = () => {
    if (!repoUrl.trim()) return;
    setIsScanning(true);
    // TODO: Implement executor client integration
    console.log("Starting scan for:", repoUrl);
  };

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-8 text-3xl font-bold">New Forensic Audit</h1>

      <div className="card">
        <label htmlFor="repo-url" className="mb-2 block text-sm text-white/70">
          Repository URL or Path
        </label>
        <input
          id="repo-url"
          type="text"
          value={repoUrl}
          onChange={(e) => setRepoUrl(e.target.value)}
          placeholder="https://github.com/owner/repo or /local/path"
          className="mb-4 w-full rounded-md border border-white/20 bg-slop-darker px-4 py-2 text-white placeholder-white/40 focus:border-slop-accent focus:outline-none"
        />

        <button onClick={handleStartScan} disabled={isScanning || !repoUrl.trim()} className="btn-primary w-full disabled:opacity-50">
          {isScanning ? "Scanning..." : "Begin Audit"}
        </button>
      </div>

      {isScanning && (
        <div className="card mt-8">
          <h2 className="mb-4 text-xl font-semibold">Forensic Feed</h2>
          <div className="space-y-2">
            <FeedItem level="info" message="Initializing analyzer..." />
            <FeedItem level="info" message="Parsing source files..." />
            {/* Events will be streamed here */}
          </div>
        </div>
      )}
    </div>
  );
}

function FeedItem({ level, message }: { level: "info" | "warning" | "error"; message: string }) {
  const colors = {
    info: "text-white/70",
    warning: "text-yellow-400",
    error: "text-red-400",
  };

  return (
    <div className={`font-mono text-sm ${colors[level]}`}>
      <span className="text-white/40">[{new Date().toLocaleTimeString()}]</span> {message}
    </div>
  );
}
