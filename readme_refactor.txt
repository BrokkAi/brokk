Brokk README Refactor Notes
Owner: README maintainers
Purpose: Persist all inputs needed to rewrite README.md with a Cline-style hero, clear USPs, Power Ranking (BPR), and media placeholders. Do NOT publish this file as the README.

1) Target outcomes
- Above-the-fold value: one-line tagline, quick links, hero GIF, BPR link/badge.
- Communicate Brokk’s USPs: fragment-level context, agentic Lutz Mode, dynamic workspace memory (Keep/Forget/Note), enterprise-grade tooling.
- Provide demos/GIFs list and filenames to produce.
- Keep existing build and JVM heap guidance.
- Tone: crisp, developer-first; similar to Cline’s README clarity without their brand styling.
- No ToC: rely on Quick Links + H2 anchors for navigation.

2) Canonical links
- Website: https://brokk.ai
- Docs: https://brokk.ai/documentation/introduction
- Discord: https://discord.com/invite/qYQ2Zg2PX7
- Blog: https://blog.brokk.ai
- YouTube: https://www.youtube.com/@Brokk_AI
- Power Ranking (BPR): https://brokk.ai/power-ranking
- Optional socials to list in Community section (approved):
  - LinkedIn: https://www.linkedin.com/company/buildwithbrokk
  - X: https://x.com/BuildWithBrokk
- Open-source Power Ranking repo (referenced in article): https://github.com/BrokkAI/powerrank
- GitHub repo: https://github.com/BrokkAi/brokk
- Releases (primary Download target): https://github.com/BrokkAi/brokk/releases

3) Tagline (candidate)
Brokk — AI-native code platform for million-line repos. Curates context on a semantic fragment level so LLMs stay on-task in large codebases.

4) Quick Links rows (hero area)
Centered row immediately under the logo:
[Website] • [Docs] • [Discord] • [Blog] • [YouTube] • [Download] • [Getting Started]

Approved targets:
- Website → https://brokk.ai
- Docs → https://brokk.ai/documentation/introduction
- Discord → https://discord.com/invite/qYQ2Zg2PX7
- Blog → https://blog.brokk.ai
- YouTube → https://www.youtube.com/@Brokk_AI
- Download → https://github.com/BrokkAi/brokk/releases
- Getting Started → in-page anchor

Optional shields row (static):
- Docs, Discord, Blog, YouTube, Power Ranking, Stars, License.

5) Brokk Power Ranking (BPR) — key facts to feature
- Public page: https://brokk.ai/power-ranking
- What it is: open-source coding benchmark with 93 real-world tasks from large repos.
- Repos used: Brokk, JGit, LangChain4j, Apache Cassandra, Apache Lucene.
- Method: single Brokk edit+test loop per task; up to 5 iterations with build/test feedback.
- Score formula for successful tasks:
  score = 1.0 / log2(build_failures + 2)
- Why it matters: shows model performance on long, messy, real tasks (up to 108k tokens).
- In-product integration: BPR meter above the Instructions panel (e.g., "BPR: 93%") that adapts to:
  - chosen model
  - current context size
  Interpretation: quick fitness indicator for the task; helps choose best cost/perf model.
- Optional note: Open-source repo link mentioned in article (if needed later): https://github.com/BrokkAI/powerrank

6) Core USPs (concise bullets for README)
- Fragment-level context
  - First-class, typed fragments beyond files:
    - summaries (top-level declarations), classes, methods, functions
    - images, markdown/text blocks, web URLs
    - exceptions/stack traces enriched with metadata
    - notes and discard-context records
- Agentic Lutz Mode (agentic context engineering)
  1) ContextAgent collects relevant fragments to answer/code.
  2) SearchAgent expands, ranks, and prunes; reorganizes the Workspace.
  3) Workspace becomes dynamic working memory.
  4) Agents explicitly discard irrelevant fragments and copy useful bits into Note fragments.
  5) Discard decisions are recorded as Discard Context fragments for transparency.
- Dynamic memory with Keep / Forget / Note
  - Explicit, traceable reasoning vs hidden context sliding.
- Enterprise-grade execution loop and tooling
  - Dependency decompilation to Java source for visibility inside libraries.
  - Incremental Java compiler to tighten edit–compile–test loop.
  - Deep Git integration: commits/diffs/PRs as context; worktrees; regression analysis.
  - BlitzForge for parallel mass refactors across hundreds of files.
  - MergeAgent uses blame-aware strategies to resolve conflicts.
  - Session/History: time-travel, branch, reuse context; no black boxes.
- Vendor-agnostic multi-model support and OSS desktop app.

7) Feature inventory (grouped for skimmable README bullets)
Context Engineering
- Quick Context: grab relevant files fast in familiar repos.
- Deep Scan: low-latency summaries build a “guided tour” of relevant files and tests.
- Search Agent: symbol-, type-, inheritance-aware; finds usage patterns beyond regex; operates across project structure and dependencies.
- Fragments as first-class citizens: summaries, classes, methods, functions, images, markdown, URLs, exceptions/stack traces, notes, discard-context.

Workspace & Preview
- Preview window: symbol/string/TODO search, quick-edit region, Flash-like fast edits for selections, capture usages into Workspace, promote to editable fragment, manual edits fold into undo/redo.

Git Integration
- Search across branches (local/remote) by commits, filenames, code.
- Capture diffs/commits/PRs as context; generate on-the-fly diffs.
- Understand regressions by diff; AI-powered cherry-pick; stash workflows tuned for fast context switching.
- First-class worktrees for parallel tasks.

Edit Loop
- Automatic compile/lint after every code change.
- Auto-run tests in workspace; failures fed back to LLM immediately.
- Tight loop enables hands-free iterative fixes.

BlitzForge
- Parallel application of instructions across many files; per-file commands with history context; post-processing by Architect agent for holistic fixes.

MergeAgent
- Conflict resolution with blame-composited strategies; minimizes manual conflict pain.

Issue Tracker Integration
- In-IDE Issues tab; default GitHub for current repo; override to different GitHub or Jira.
- Capture issue + comments + images into Workspace as structured fragments.
- Open in browser, copy description, filters, search, refresh.

Dependency Intelligence
- Decompile libraries to source in seconds so the model sees exact APIs/versions when needed.

Session & History
- Sessions with action history; undo/redo; branch at any point; copy workspaces; transparency into exactly what the AI saw.

8) Social proof (quote for README)
"With Brokk, it is easy to find the right context and get better results... Compared to Brokk, Cursor is really primitive."
— Adam Bien, airhacks.fm

9) Personas (for a brief "Who is it for?")
- Enterprise Staff/Principal engineers on decade-old services.
- Platform/Tooling leads targeting 5x team gains.
- Architects driving large modernization projects.
- Power users who hit scaling limits with standard AI editors.

10) Hero and demo media plan (filenames and alts)
Folder convention: docs/media
- Hero (Lutz Mode): docs/media/hero-lutz-mode.gif
  Alt: "Brokk Lutz Mode: collect relevant fragments -> prune -> note/discard -> workspace ready"
  Target: 6–8 seconds, < 8 MB, 1280x720 or 1440x900.
Storyboard (quick):
  1) User question overlay (e.g., Blender spin tool bug).
  2) ContextAgent: fragments (class, method, summary, URL, image, stack trace) fly in.
  3) SearchAgent: pruning; some fragments fade out.
  4) Notes and Discard fragments appear with brief labels.
  5) "Workspace Ready (N fragments). Exactly the right context — nothing more."
- BlitzForge demo: docs/media/feature-blitzforge.gif
  Alt: "BlitzForge mass refactor across repo"
- MergeAgent demo: docs/media/feature-mergeagent.gif
  Alt: "MergeAgent resolves conflicts using blame-aware strategy"
- Issue capture: docs/media/feature-issue-capture.gif
  Alt: "Capture a GitHub/Jira issue as structured context fragments"
- BPR meter screenshot: docs/media/screenshot-bpr-meter.png
  Alt: "In-app BPR meter above Instructions panel"
General media tips:
- Keep overlays minimal; consistent dark theme; 1x and 2x exports when possible.
- Avoid Git LFS if sizes stay moderate.

11) Proposed README structure (outline only)
- Header: logo
- Quick Links row (Website, Docs, Discord, Blog, YouTube, Download, Getting Started)
- Shields row (Docs, Discord, Blog, YouTube, Power Ranking, Stars, License)
- H1: Brokk — AI-native code platform for million-line repos
- One-paragraph pitch (fragment-level context + Lutz Mode + transparency)
- Hero GIF
- Why Brokk is different (3–5 bullets with the USPs above)
- Brokk Power Ranking (BPR) section with link and brief explanation + optional meter screenshot
- How it works (20-second flow: ask -> collect -> prune -> code+test loop -> commit)
- Highlights (compact bullet list of features grouped as in section 7)
- Demos (collapsible <details> with GIFs)
- Getting Started (Download links; short list; include JBang/jdeploy options)
- Build from source (JDK 21+; Gradle commands)
- Increase JVM heap (JAVA_TOOL_OPTIONS examples)
- Headless Executor CLI (docs/headless-exec-cli.md)
- Who is it for? (personas)
- Community (Discord, YouTube, Blog; LinkedIn/X)
- License
- No ToC at the top (policy).

12) Existing content to preserve from current README
- Running Brokk (sign up + download)
- Contributing (Gradle + Scala; pnpm managed by Gradle; commands: run, test, build, shadowJar, tidy)
- JVM heap instructions using JAVA_TOOL_OPTIONS with examples for macOS/Linux/Windows
- Link to development.md:
  https://github.com/BrokkAi/brokk/tree/master/app/src/main/development.md

13) Short copy blocks (ready to drop into README when writing)
Why Brokk is different (short draft):
- Fragment-level context, not file blobs. Classes, methods, summaries, stack traces, URLs, images, notes, and discard records are first-class fragments.
- Agentic Lutz Mode. ContextAgent collects; SearchAgent expands and prunes; the Workspace becomes working memory. Keep/Forget/Note decisions are explicit and traceable.
- Built for enterprise scale. Deep Git + worktrees, dependency decompilation, an incremental Java compiler, BlitzForge mass refactors, and MergeAgent for conflicts.

BPR (short draft):
The Brokk Power Ranking is our open benchmark of 93 real-world coding tasks across major Java repos. See live results: https://brokk.ai/power-ranking. In-app, the BPR meter (e.g., "BPR: 93%") adapts to your selected model and context size to estimate task fitness so you can pick the best cost/performance model before you start.

How it works (20 seconds) (short draft):
1) Describe your goal. 2) ContextAgent pulls in the minimal relevant fragments. 3) SearchAgent ranks and prunes; Notes and Discards record decisions. 4) Brokk edits and runs tests in a tight loop. 5) Commit, branch, and review without leaving the workspace.

14) Compliance notes
- Avoid unverified performance claims; anchor value in BPR and capabilities.
- Keep language vendor-agnostic; no competitor bashing.
- Use plain ASCII punctuation in README.

15) Open items before publishing
- Confirm quote usage from Adam Bien is acceptable on README.
- Provide the final hero GIF and demo assets at the paths listed in section 10.
- Provide the BPR meter screenshot (docs/media/screenshot-bpr-meter.png).
- Fill in JBang/jdeploy install commands (placeholders below).

16) Cline README layout blueprint (visual and structural spec)
Wireframe (top to bottom):
1) Logo (centered)
2) Quick Links row (centered inline links)
3) Shields row (centered badges)
4) H1 + one-line tagline (concise)
5) Hero GIF (centered, 800–900px width)
6) Opening paragraph (2–3 short sentences)
7) "How it works" mini-list (3–5 steps)
8) Feature blocks (repeatable pattern):
   - H2 section title
   - Short paragraph (3–5 lines max)
   - One media element (GIF or image), centered full-width under text
   Suggested blocks for Brokk:
     a) Fragment-level context
     b) Lutz Mode (collect -> prune -> note/discard)
     c) Search Agent / Deep Scan
     d) Git integration (commits/diffs/worktrees)
     e) BlitzForge
     f) MergeAgent
     g) Issue capture
9) BPR section (H2) with link and optional meter screenshot
10) Demos (<details> collapsible) with 3–5s GIFs
11) Getting Started / Build / Headless CLI
12) Who is it for? (personas)
13) Community & Social (Discord, YouTube, Blog; LinkedIn/X)
14) Contributing / License
Conventions:
- No ToC. Depend on Quick Links + H2 anchors for navigation.
- Keep above-the-fold to: H1, hero GIF, and a short “why it’s different” block.
- Paragraphs: 2–5 sentences; avoid walls of text.
- GIF budget: each ≤ 8 MB; hero 6–8s; features 3–5s.
- Always include alt text; dark theme visuals; minimal overlays.

17) Shields/badges plan (exact markup)
Centered shields row HTML:
<p align="center">
  <a href="https://brokk.ai/documentation/introduction"><img alt="Docs" src="https://img.shields.io/badge/Docs-brokk.ai-0b72ff?logo=readthedocs&logoColor=white"></a>
  <a href="https://discord.com/invite/qYQ2Zg2PX7"><img alt="Discord" src="https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white"></a>
  <a href="https://blog.brokk.ai"><img alt="Blog" src="https://img.shields.io/badge/Blog-latest-ff6a00?logo=ghost&logoColor=white"></a>
  <a href="https://www.youtube.com/@Brokk_AI"><img alt="YouTube" src="https://img.shields.io/badge/YouTube-subscribe-ff0000?logo=youtube&logoColor=white"></a>
  <a href="https://brokk.ai/power-ranking"><img alt="Power Ranking" src="https://img.shields.io/badge/Power%20Ranking-live-7b61ff"></a>
  <a href="https://github.com/BrokkAi/brokk/stargazers"><img alt="GitHub Stars" src="https://img.shields.io/github/stars/BrokkAi/brokk?style=social"></a>
  <a href="https://github.com/BrokkAi/brokk/blob/master/LICENSE"><img alt="License" src="https://img.shields.io/github/license/BrokkAi/brokk"></a>
</p>

18) Quick Links markup (exact)
<p align="center">
  <a href="https://brokk.ai">Website</a> •
  <a href="https://brokk.ai/documentation/introduction">Docs</a> •
  <a href="https://discord.com/invite/qYQ2Zg2PX7">Discord</a> •
  <a href="https://blog.brokk.ai">Blog</a> •
  <a href="https://www.youtube.com/@Brokk_AI">YouTube</a> •
  <a href="https://github.com/BrokkAi/brokk/releases">Download</a> •
  <a href="#getting-started">Getting Started</a>
</p>

19) Accessibility and performance budgets
- Images/GIFs must include descriptive alt text.
- GIF constraints: hero ≤ 8 MB; features ≤ 4 MB; 1280x720 or 1440x900.
- Prefer 30 fps or less; reduce colors to shrink size; provide optional link to MP4/WebM for heavier clips.
- Avoid flashing content; ensure high-contrast overlay text.
- Keep filenames lowercase-hyphenated; store under docs/media; avoid base64 inline data.

20) Install options (Download, JBang, jdeploy) — placeholders
Primary Download:
- Releases page: https://github.com/BrokkAi/brokk/releases

JBang (placeholder; fill actual alias/coords):
- Command placeholder:
  jbang app install --name brokk <group:artifact:version or script URL>
- Alternate run placeholder:
  jbang <entry-point.java or alias>
TODO: provide exact GAV and alias once published.

jdeploy (placeholder; fill actual package name):
- Install placeholder:
  npx jdeploy install <npm-package-name>
- Run placeholder:
  <installed-command>
TODO: update with real npm package name and command.

21) Anchors and ToC policy
- No ToC block at the top.
- Ensure each major section has a clear H2 with a stable, human-readable title:
  - Why Brokk is different
  - Brokk Power Ranking (BPR)
  - How it works
  - Highlights
  - Demos
  - Getting Started
  - Build from source
  - Increase JVM heap
  - Headless Executor CLI
  - Who is it for?
  - Community
  - License

22) README rewrite checklist (for PR author)
- [ ] Add Quick Links row and Shields row using sections 17–18.
- [ ] Insert H1, one-line tagline, and hero GIF (section 10).
- [ ] Add “Why Brokk is different” bullets (section 13).
- [ ] Add BPR section + link and screenshot (sections 5 and 10).
- [ ] Add “How it works” mini-list (section 13).
- [ ] Insert Highlights with grouped features (section 7).
- [ ] Add Demos <details> with GIF placeholders (section 10).
- [ ] Add Getting Started with Releases link and JBang/jdeploy placeholders (sections 11 and 20).
- [ ] Preserve build, heap, CLI, and development.md links (section 12).
- [ ] Add Community with LinkedIn/X (section 2).
- [ ] Verify badges resolve and license badge matches repo license.
- [ ] Run a final pass for ASCII punctuation, short paragraphs, and consistent alt text.

End of notes.
