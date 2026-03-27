import { useParams } from "react-router-dom";
import { useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { apiClient, type ScanResult, type ScanStatus } from "../lib/api-client";

export function DashboardPage() {
  const { id } = useParams<{ id: string }>();
  const [scan, setScan] = useState<ScanStatus | null>(null);
  const [results, setResults] = useState<ScanResult | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    apiClient.getScan(id).then((data) => {
      setScan(data);
      if (data.result_json) {
        setResults(JSON.parse(data.result_json));
      }
      setLoading(false);
    });
  }, [id]);

  if (loading) return <div className="animate-pulse text-slop-accent">Loading Audit...</div>;
  if (!scan) return <div className="text-slop-red">Scan not found.</div>;

  const totalLiability = results?.findings.reduce((sum, f) => sum + f.impact, 0) || 0;

  return (
    <div>
      <h1 className="mb-8 text-3xl font-bold">
        Interactive Tax Bill
        <span className="ml-2 text-sm font-normal text-white/50">Audit: {id}</span>
      </h1>

      <div className="grid gap-6 md:grid-cols-2">
        {/* Maintenance Liability Summary */}
        <div className="card col-span-2">
          <h2 className="mb-4 text-xl font-semibold text-slop-accent">Maintenance Liability</h2>
          <div className="text-5xl font-bold text-slop-red">${totalLiability.toLocaleString()}</div>
          <p className="mt-2 text-white/60">Estimated annual cost to maintain this codebase</p>
        </div>

        {/* Ownership Heatmap Placeholder */}
        <div className="card">
          <h2 className="mb-4 text-xl font-semibold">Ownership Heatmap</h2>
          <div className="flex h-64 items-center justify-center rounded-md bg-slop-darker text-white/40">
            D3 Visualization Placeholder
          </div>
        </div>

        {/* Hallucination Ledger */}
        <div className="card">
          <h2 className="mb-4 text-xl font-semibold">Hallucination Ledger</h2>
          <div className="space-y-2">
            {results?.findings.map((f, i) => (
              <LedgerItem
                key={i}
                location={f.location}
                finding={f.finding}
                impact={f.impact}
              />
            )) || <p className="text-white/40 italic">No findings recorded.</p>}
          </div>
        </div>

        {/* Full Analysis Report */}
        {results?.markdownReport && (
          <div className="card col-span-2 mt-6">
            <h2 className="mb-6 text-xl font-semibold text-slop-accent">Forensic Audit Report</h2>
            <div className="prose prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {results.markdownReport}
              </ReactMarkdown>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function LedgerItem({
  location,
  finding,
  impact,
}: {
  location: string;
  finding: string;
  impact: number;
}) {
  return (
    <div className="rounded-md border border-white/10 bg-slop-darker p-3">
      <div className="font-mono text-sm text-slop-accent">{location}</div>
      <div className="text-sm text-white/70">{finding}</div>
      <div className="mt-1 text-sm font-semibold text-slop-red">${impact.toLocaleString()}</div>
    </div>
  );
}
