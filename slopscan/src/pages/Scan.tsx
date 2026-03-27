import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { apiClient } from "../lib/api-client";

function parseLogs(rawLogs: string) {
  const lines: string[] = [];
  let currentLine = "";
  let progress = 0;

  for (let i = 0; i < rawLogs.length; i++) {
    const char = rawLogs[i];
    if (char === "\r") {
      currentLine = "";
    } else if (char === "\n") {
      if (currentLine.trim()) {
        lines.push(currentLine);
      }
      currentLine = "";
    } else {
      currentLine += char;
    }

    // Check for percentage in the active line buffer
    const match = currentLine.match(/(\d+)%/);
    if (match) {
      progress = parseInt(match[1], 10);
    }
  }

  // Push remaining buffer if not empty
  if (currentLine.trim()) {
    lines.push(currentLine);
  }

  return { lines, progress };
}

export function ScanPage() {
  const [repoUrl, setRepoUrl] = useState("");
  const [isScanning, setIsScanning] = useState(false);
  const [scanId, setScanId] = useState<string | null>(null);
  const [status, setStatus] = useState<string>("Initializing...");
  const [progress, setProgress] = useState(0);
  const [feed, setFeed] = useState<Array<{ level: "info" | "warning" | "error"; message: string }>>([]);
  const navigate = useNavigate();

  const addLog = (message: any, level: "info" | "warning" | "error" = "info") => {
    const stringMessage =
      typeof message === "string"
        ? message
        : typeof message === "object" && message !== null
        ? JSON.stringify(message)
        : String(message);
    setFeed((prev) => [...prev, { level, message: stringMessage }]);
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

    const poll = async () => {
      try {
        const scan = await apiClient.getScan(scanId);
        setStatus(scan.status);

        if (scan.logs && typeof scan.logs === "string") {
          const { lines, progress: latestProgress } = parseLogs(scan.logs);
          setFeed(lines.map((l) => ({ level: "info", message: String(l) })));
          setProgress(latestProgress);
        }

        if (scan.status === "CLONED") {
          addLog("Repository cloned. Starting deep analysis...");
        } else if (scan.status === "COMPLETED") {
          addLog("Analysis complete!", "info");
          setIsScanning(false);
          setTimeout(() => navigate(`/scan-result/${scanId}`), 1000);
        } else if (scan.status === "FAILED") {
          let errorMsg: any = "Unknown error";
          if (scan.result_json) {
            try {
              const parsed = JSON.parse(scan.result_json);
              errorMsg = parsed.error ?? scan.result_json;
            } catch (e) {
              errorMsg = scan.result_json;
            }
          }
          addLog(`Analysis failed: ${typeof errorMsg === "string" ? errorMsg : JSON.stringify(errorMsg)}`, "error");
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

          {((progress > 0 && status === "PENDING") || status === "CLONED") && (
            <div className="mb-4">
              <div className="mb-1 flex justify-between text-xs font-mono text-white/50">
                <span>
                  {status === "CLONED"
                    ? "Spinning up analysis server..."
                    : progress === 100
                    ? "Finalizing clone..."
                    : "Cloning Progress"}
                </span>
                <span>{status === "CLONED" ? "" : `${progress}%`}</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-slop-darker">
                <div
                  className={`h-full bg-slop-accent transition-all duration-500 ease-out ${
                    progress === 100 || status === "CLONED" ? "animate-pulse" : ""
                  } ${status === "CLONED" ? "w-full opacity-75" : ""}`}
                  style={{ width: status === "CLONED" ? undefined : `${progress}%` }}
                />
              </div>
            </div>
          )}

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
