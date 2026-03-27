import { useParams } from "react-router-dom";

export function DashboardPage() {
  const { jobId } = useParams<{ jobId: string }>();

  // TODO: Fetch job results and render dashboard

  return (
    <div>
      <h1 className="mb-8 text-3xl font-bold">
        Interactive Tax Bill
        <span className="ml-2 text-sm font-normal text-white/50">Job: {jobId}</span>
      </h1>

      <div className="grid gap-6 md:grid-cols-2">
        {/* Maintenance Liability Summary */}
        <div className="card col-span-2">
          <h2 className="mb-4 text-xl font-semibold text-slop-accent">Maintenance Liability</h2>
          <div className="text-5xl font-bold text-slop-red">$12,340</div>
          <p className="mt-2 text-white/60">Estimated annual cost to maintain this codebase</p>
        </div>

        {/* Ownership Heatmap Placeholder */}
        <div className="card">
          <h2 className="mb-4 text-xl font-semibold">Ownership Heatmap</h2>
          <div className="flex h-64 items-center justify-center rounded-md bg-slop-darker text-white/40">
            D3 Visualization Placeholder
          </div>
        </div>

        {/* Hallucination Ledger Placeholder */}
        <div className="card">
          <h2 className="mb-4 text-xl font-semibold">Hallucination Ledger</h2>
          <div className="space-y-2">
            <LedgerItem
              location="src/services/PaymentProcessor.java:142"
              finding="Cyclomatic complexity: 47"
              impact={2340}
            />
            <LedgerItem
              location="src/utils/DateHelper.java:89"
              finding="'How' comment ratio: 94%"
              impact={890}
            />
          </div>
        </div>
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
