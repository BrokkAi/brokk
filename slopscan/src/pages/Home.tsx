import { Link } from "react-router-dom";

export function HomePage() {
  return (
    <div className="flex flex-col items-center justify-center py-20">
      <h1 className="mb-4 text-5xl font-bold">
        <span className="text-slop-red">Slop</span>Scan
      </h1>
      <p className="mb-8 max-w-xl text-center text-xl text-white/70">
        A cringe-inducing forensic audit of your codebase. Translate abstract cognitive debt into a
        literal dollar-value maintenance liability.
      </p>
      <Link to="/scan" className="btn-primary text-lg">
        Start Forensic Audit
      </Link>

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
