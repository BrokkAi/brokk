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

  let displayMarkdown = results?.markdownReport || "";
  let extractedCost = 0;

  const costRegex = /est_annual_dev_cost=\$([\d,]+)/;
  const match = displayMarkdown.match(costRegex);

  if (match) {
    extractedCost = parseInt(match[1].replace(/,/g, ""), 10);
    displayMarkdown = displayMarkdown.replace(match[0], "").trim();
  }

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
          <div className="text-5xl font-bold text-slop-red">
            ${extractedCost.toLocaleString()}
          </div>
          <p className="mt-2 text-white/60">Estimated annual cost to maintain this codebase</p>
        </div>

        {/* Full Analysis Report */}
        {displayMarkdown && (
          <div className="card col-span-2 mt-6">
            <h2 className="mb-6 text-xl font-semibold text-slop-accent">Forensic Audit Report</h2>
            <div className="prose prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {displayMarkdown}
              </ReactMarkdown>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
