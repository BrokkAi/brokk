import { Outlet, Link } from "react-router-dom";

export function Layout() {
  return (
    <div className="min-h-screen bg-slop-darker">
      <header className="border-b border-white/10 bg-slop-dark">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-4">
          <Link to="/" className="flex items-center gap-2">
            <span className="text-2xl font-bold text-slop-red">SlopScan</span>
            <span className="text-sm text-white/60">Forensic Audit</span>
          </Link>
          <nav className="flex gap-4">
            <Link to="/" className="text-white/80 transition-colors hover:text-white">
              Home
            </Link>
            <Link to="/scan" className="text-white/80 transition-colors hover:text-white">
              New Scan
            </Link>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  );
}
