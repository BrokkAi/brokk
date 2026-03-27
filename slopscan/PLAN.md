# SlopScan: Code Quality Forensic Audit

## Vision

SlopScan is a web-based "Cringe-Inducing Forensic Audit" dashboard for engineering leaders. It transforms abstract code quality metrics into actionable, shareable insights that translate cognitive debt into maintenance liability.

## Core Features

### Phase 1: Foundation
- [ ] React 18 + TypeScript + Vite scaffold
- [ ] ESLint + Prettier configuration
- [ ] Tailwind CSS for modern dashboard styling
- [ ] Basic routing (React Router)
- [ ] Executor client library (HTTP + polling for events)

### Phase 2: Authentication & Repository Selection
- [ ] GitHub OAuth integration (reuse Brokk's device flow)
- [ ] Repository picker UI
- [ ] Clone/workspace initialization flow

### Phase 3: Live Forensic Feed
- [ ] Real-time event stream display during scan
- [ ] Progress indicators for AST parsing
- [ ] "Cringe findings" cards as they're detected
- [ ] Pattern drift notifications

### Phase 4: Analysis Engine Integration
- [ ] Cyclomatic complexity computation (via new Java tools)
- [ ] Comment semantic detection (how vs. why ratio)
- [ ] Ownership heatmap (git log correlation)
- [ ] "Hallucination-prone" code detection via LLM

### Phase 5: Interactive Tax Bill Dashboard
- [ ] Dollar-value "Maintenance Liability" calculator
- [ ] Ownership Heatmap visualization (D3 or Recharts)
- [ ] Hallucination Ledger with specific brittle logic items
- [ ] Export/screenshot functionality for sharing

## Technical Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SlopScan Web Portal                          │
├─────────────────────────────────────────────────────────────────────┤
│  React + TypeScript + Vite                                          │
│                                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │  Auth Flow  │  │  Scan View  │  │  Dashboard  │                 │
│  │  (GitHub)   │  │  (Live Feed)│  │  (Tax Bill) │                 │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘                 │
│         │                │                │                         │
│         └────────────────┼────────────────┘                         │
│                          │                                          │
│                    ExecutorClient                                   │
│                          │                                          │
└──────────────────────────┼──────────────────────────────────────────┘
                           │ HTTP/JSON
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Brokk Headless Executor                          │
│                                                                      │
│  Existing:                      New (to build):                      │
│  - SearchTools (AST analysis)   - CyclomaticComplexityTool           │
│  - getGitLog (ownership)        - CommentSemanticsTool               │
│  - LLM integration              - OwnershipHeatmapTool               │
│  - Event streaming              - Custom SLOP_FINDING events         │
└──────────────────────────────────────────────────────────────────────┘
```

## API Integration Points

### From Existing Brokk Executor

| Endpoint | Purpose |
|----------|---------|
| `POST /v1/jobs` | Submit scan jobs with `mode: SEARCH` or custom `SLOP_SCAN` |
| `GET /v1/jobs/{id}/events` | Poll for findings and progress |
| `POST /v1/github/oauth/start` | GitHub device flow authentication |
| `GET /v1/context` | Current workspace state |

### New Endpoints Needed (Java side)

| Endpoint | Purpose |
|----------|---------|
| `POST /v1/slopscan/analyze` | Submit a full SlopScan analysis job |
| Custom event types | `SLOP_FINDING`, `COMPLEXITY_METRIC`, `OWNERSHIP_DATA` |

## File Structure

```
slopscan/
├── src/
│   ├── components/
│   │   ├── ForensicFeed/       # Live scan findings
│   │   ├── TaxBill/            # Final dashboard
│   │   ├── OwnershipHeatmap/   # D3 visualization
│   │   └── common/             # Shared UI components
│   ├── hooks/
│   │   ├── useExecutor.ts      # Executor client hook
│   │   └── useEventStream.ts   # Polling/SSE hook
│   ├── lib/
│   │   └── executor-client.ts  # HTTP client for Brokk API
│   ├── pages/
│   │   ├── Login.tsx
│   │   ├── RepoSelect.tsx
│   │   ├── Scan.tsx
│   │   └── Dashboard.tsx
│   ├── App.tsx
│   └── main.tsx
├── AGENTS.md
├── PLAN.md
├── package.json
├── tsconfig.json
├── vite.config.ts
├── eslint.config.js
├── .prettierrc
└── tailwind.config.js
```

## Success Metrics

- **Scan Duration**: Full repo analysis in < 2 minutes for typical projects
- **Shareability**: Dashboard renders cleanly when screenshotted
- **Engagement**: "Wait, do we actually know how this works?" moments per scan

## Open Questions

1. Should the executor be spawned by the web app or run as a service?
2. How to handle multi-repo comparisons?
3. Pricing model integration (if any)?
