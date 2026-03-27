import { Link } from "react-router-dom";
import { useEffect, useState } from "react";
import { apiClient } from "../lib/api-client";

export function HomePage() {
  const [isHealthy, setIsHealthy] = useState<boolean | null>(null);
  const [isChecking, setIsChecking] = useState(true);

  const checkHealth = async () => {
    setIsChecking(true);
    try {
      const healthy = await apiClient.checkHealth();
      setIsHealthy(healthy);
    } catch (err) {
      setIsHealthy(false);
    } finally {
      setIsChecking(false);
    }
  };

  useEffect(() => {
    checkHealth();
  }, []);

  return (
    <div className="flex flex-col items-center justify-center py-20">
      {!isChecking && isHealthy === false && (
        <div className="mb-8 flex w-full max-w-2xl items-center justify-between rounded-lg border border-slop-red/50 bg-slop-red/10 p-4 text-slop-red">
          <div className="flex items-center gap-3">
            <span className="text-2xl">⚠️</span>
            <div>
              <p className="font-bold">Service Unavailable</p>
              <p className="text-sm opacity-90">
                The forensic engine is offline. Audit capabilities are currently restricted.
              </p>
            </div>
          </div>
          <button
            onClick={checkHealth}
            className="rounded bg-slop-red px-4 py-2 text-sm font-bold text-white transition-colors hover:bg-red-600"
          >
            Retry
          </button>
        </div>
      )}

      <h1 className="mb-4 text-5xl font-bold">
        <span className="text-slop-red">Slop</span>Scan
      </h1>
      <p className="mb-8 max-w-xl text-center text-xl text-white/70">
        A cringe-inducing forensic audit of your codebase. Translate abstract cognitive debt into a
        literal dollar-value maintenance liability.
      </p>
      {isHealthy === false ? (
        <button disabled className="btn-primary text-lg opacity-50 cursor-not-allowed">
          Start Forensic Audit
        </button>
      ) : (
        <Link to="/scan" className="btn-primary text-lg">
          Start Forensic Audit
        </Link>
      )}

      <div className="mt-16 grid gap-8 md:grid-cols-3">
        <FeatureCard
          title="Cyclomatic Complexity"
          description="Identify methods with unearned complexity that inflate maintenance costs."
        />
        <FeatureCard
          title="Comment Semantics"
          description="Detect comments that explain 'how' instead of 'why' - a hallmark of fragile code."
        />
        <FeatureCard
          title="Ownership Heatmap"
          description="Highlight 'dark zones' where code complexity outstrips human commit velocity."
        />
      </div>
    </div>
  );
}

function FeatureCard({ title, description }: { title: string; description: string }) {
  return (
    <div className="card">
      <h3 className="mb-2 text-lg font-semibold text-slop-accent">{title}</h3>
      <p className="text-white/70">{description}</p>
    </div>
  );
}
