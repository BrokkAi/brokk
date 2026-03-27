import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { apiClient } from "../lib/api-client";

export function ScanPage() {
  const [repoUrl, setRepoUrl] = useState("");
  const [isScanning, setIsScanning] = useState(false);
  const [scanId, setScanId] = useState<string | null>(null);
  const [status, setStatus] = useState<string>("Initializing...");
  const [feed, setFeed] = useState<Array<{ level: "info" | "warning" | "error"; message: string }>>([]);
  const navigate = useNavigate();

  const addLog = (message: string, level: "info" | "warning" | "error" = "info") => {
    setFeed((prev) => [...prev, { level, message }]);
  };

  const handleStartScan = async () => {
    if (!repoUrl.trim()) return;
    setIsScanning(true);
    setFeed([]);
    try {
      addLog(`Starting scan for: ${repoUrl}`);
      const { id } = await apiClient.startScan(repoUrl);
      setScanId(id);
      addLog(`Scan registered: ${id}`);
    } catch (err) {
      addLog(err instanceof Error ? err.message : "Failed to start scan", "error");
      setIsScanning(false);
    }
  };

  useEffect(() => {
    if (!scanId || !isScanning) return;

    let lastLogLength = 0;

    const poll = async () => {
      try {
        const scan = await apiClient.getScan(scanId);
        setStatus(scan.status);

        if (scan.logs && scan.logs.length > lastLogLength) {
          const newLogs = scan.logs.substring(lastLogLength);
          // Split by newline and add each non-empty line to the feed
          newLogs.split(/\r?\n/).forEach((line) => {
            if (line.trim()) {
              addLog(line);
            }
          });
          lastLogLength = scan.logs.length;
        }
        
        if (scan.status === 'CLONED') {
          addLog("Repository cloned. Starting deep analysis...");
        } else if (scan.status === 'COMPLETED') {
          addLog("Analysis complete!", "info");
          setIsScanning(false);
          setTimeout(() => navigate(`/scan-result/${scanId}`), 1000);
        } else if (scan.status === 'FAILED') {
          const errorMsg = scan.result_json ? JSON.parse(scan.result_json).error : "Unknown error";
          addLog(`Analysis failed: ${errorMsg}`, "error");
          setIsScanning(false);
        }
      } catch (err) {
        addLog("Error polling status", "error");
      }
    };

    const interval = setInterval(poll, 2000);
    return () => clearInterval(interval);
  }, [scanId, isScanning, navigate]);

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
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-xl font-semibold">Forensic Feed</h2>
            <span className="animate-pulse text-xs font-mono text-slop-accent">{status}</span>
          </div>
          <div className="max-h-64 overflow-y-auto space-y-2">
            {feed.map((item, i) => (
              <FeedItem key={i} level={item.level} message={item.message} />
            ))}
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
