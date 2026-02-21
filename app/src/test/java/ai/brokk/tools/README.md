# Brokk Test Tools

This package contains command-line benchmark and evaluation tools for testing Brokk's analyzer capabilities.

## UsageBenchEval

A CLI benchmark tool that evaluates `FuzzyUsageFinder` against labeled datasets, computing precision, recall, and F1 metrics.
Such a dataset can be found and generated at [usagebench](https://github.com/BrokkAi/usagebench).

### Purpose

UsageBenchEval compares the fuzzy usage finder's detected code unit usages against ground-truth labels to measure accuracy. For each code unit in the dataset, it:

1. Calls `FuzzyUsageFinder.findUsages(fqName)` to detect usages
2. Compares detected enclosing code units against expected usages from the JSON labels
3. Computes true positives, false positives, and false negatives
4. Calculates precision, recall, and F1 score

### Dataset Structure

The tool expects a dataset organized as follows:

```
dataset/
  <language>/                    # Directory named by Language.internalName (e.g., "java", "python")
    <project>/                   # Project source directory
      src/
        ...                      # Source files
    <project>-usages.json        # Ground truth labels for the project
```

For example:
```
dataset/
  java/
    commons-lang/
      src/main/java/...
    commons-lang-usages.json
    guava/
      src/main/java/...
    guava-usages.json
```

### Ground Truth JSON Format

Each `<project>-usages.json` file contains labeled usages in the following format:

```json
{
  "codeUnits": [
    {
      "fullyQualifiedName": "com.example.MyClass.myMethod",
      "type": "FUNCTION",
      "usages": [
        {
          "fullyQualifiedName": "com.example.CallerClass.callerMethod",
          "lineNumber": 42
        }
      ]
    }
  ]
}
```

- `fullyQualifiedName`: The fully qualified name of the code unit being searched for
- `type`: The code unit type (e.g., "CLASS", "FUNCTION", "FIELD")
- `usages`: List of locations where this code unit is used
  - `fullyQualifiedName`: The enclosing code unit where the usage occurs
  - `lineNumber`: Line number of the usage (currently not used for matching)

### Usage

Run via Gradle:

```bash
# Show help
./gradlew :app:runUsageBenchEval -Pargs="--help"

# Evaluate all projects in a dataset
./gradlew :app:runUsageBenchEval -Pargs="--input-dir /path/to/dataset"

# Filter by language
./gradlew :app:runUsageBenchEval -Pargs="--input-dir /path/to/dataset --language JAVA"

# Evaluate specific projects
./gradlew :app:runUsageBenchEval -Pargs="--input-dir /path/to/dataset --projects commons-lang --projects guava"

# Specify output directory
./gradlew :app:runUsageBenchEval -Pargs="--input-dir /path/to/dataset --output /path/to/results"
```

### CLI Options

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `--input-dir` | Yes | - | Path to the dataset root directory |
| `--language` | No | `ALL` | Filter by `Language.internalName` (e.g., `JAVA`, `PYTHON`), or `ALL` for all languages |
| `--output` | No | `./usage-results` | Output directory for result JSON files |
| `--projects` | No | - | Specific project names to evaluate (repeatable) |
| `--help` | No | - | Show usage help |

### Output

The tool writes four JSON files to the output directory:

1. **`summary.json`** - Aggregate metrics and per-project results:
   ```json
   {
     "projects": [
       {
         "project": "commons-lang",
         "language": "JAVA",
         "truePositives": 150,
         "falsePositives": 12,
         "falseNegatives": 8,
         "precision": 0.926,
         "recall": 0.949,
         "f1": 0.937
       }
     ],
     "aggregate": {
       "totalTP": 150,
       "totalFP": 12,
       "totalFN": 8,
       "precision": 0.926,
       "recall": 0.949,
       "f1": 0.937
     }
   }
   ```

2. **`true-positives.json`** - Correctly detected usages grouped by searched code unit.
3. **`false-positives.json`** - Incorrectly detected usages.
4. **`false-negatives.json`** - Expected usages that were not detected.

Files 2-4 share a common structure:
```json
{
  "codeUnits": [
    {
      "searchedFqn": "com.example.MyClass.myMethod",
      "searchedFilePath": "/abs/path/to/MyClass.java",
      "project": "commons-lang",
      "projectPath": "/abs/path/to/commons-lang",
      "language": "JAVA",
      "usages": [
        {
          "fqName": "com.example.Caller.call",
          "snippet": "public void call() { myMethod(); }",
          "filePath": "/abs/path/to/Caller.java"
        }
      ]
    }
  ]
}
```
*Note: For `false-negatives.json`, the `snippet` and `filePath` in `usages` will be empty as these usages were not detected in the source.*

### Metrics

- **True Positive (TP)**: A usage detected by FuzzyUsageFinder that matches an expected usage (by enclosing FQN)
- **False Positive (FP)**: A usage detected by FuzzyUsageFinder that is not in the expected usages
- **False Negative (FN)**: An expected usage that FuzzyUsageFinder did not detect
- **Precision**: TP / (TP + FP) - What fraction of detected usages are correct
- **Recall**: TP / (TP + FN) - What fraction of expected usages were found
- **F1 Score**: Harmonic mean of precision and recall


### Example Output

```
================================================================================
 UsageBenchEval - FuzzyUsageFinder Benchmark
================================================================================
 Input Directory: /home/user/dataset
 Language Filter: ALL
 Output File:     /home/user/usage-results
 Projects Found:  3
--------------------------------------------------------------------------------
Evaluating project: commons-lang (JAVA)...
  TP=150, FP=12, FN=8, P=0.926, R=0.949, F1=0.937
Evaluating project: guava (JAVA)...
  TP=200, FP=15, FN=10, P=0.930, R=0.952, F1=0.941
--------------------------------------------------------------------------------
 Summary Results
--------------------------------------------------------------------------------
 Total TP: 350
 Total FP: 27
 Total FN: 18
 Precision: 0.928
 Recall:    0.951
 F1 Score:  0.939
================================================================================
 Results written to directory: /home/user/usage-results
  - summary.json
  - true-positives.json
  - false-positives.json
  - false-negatives.json
```

---

## UsageResultsExplorer

A Swing GUI dialog for visually browsing UsageBenchEval output results, allowing interactive exploration of true positives and false positives with syntax-highlighted code snippets.

### Purpose

After running `UsageBenchEval`, the explorer provides a visual interface to:

1. View aggregate metrics (TP, FP, FN, precision, recall, F1) at a glance
2. Browse true positives and false positives in separate tabs
3. Select individual code units to see their usage snippets with Java syntax highlighting
4. Navigate through results to understand where the fuzzy usage finder succeeded or failed

### Usage

Run via Gradle:

```bash
# Open the explorer for a results directory
./gradlew :app:runUsageResultsExplorer -Pargs="./usage-results"

# Or with an absolute path
./gradlew :app:runUsageResultsExplorer -Pargs="/path/to/usage-results"
```

Alternatively, run directly from the command line:

```bash
java -cp <classpath> ai.brokk.tools.UsageResultsExplorer /path/to/usage-results
```

The argument is the path to the results directory created by `UsageBenchEval` (the `--output` directory).

### Input

The explorer expects a directory containing the three JSON files produced by `UsageBenchEval`:

- `summary.json` - Aggregate and per-project metrics
- `true-positives.json` - Correctly detected usages
- `false-positives.json` - Incorrectly detected usages

### UI Layout

```
+------------------------------------------------------------------+
| UsageBenchEval Results Explorer                                   |
+------------------------------------------------------------------+
| Aggregate Metrics                                                 |
| TP: 350  FP: 27  FN: 18  Precision: 0.928  Recall: 0.951  F1: 0.939 |
+------------------------------------------------------------------+
| [True Positives] [False Positives] [False Negatives]              |
+------------------------------------------------------------------+
| v Project: commons-lang (5 units)     |                           |
|   - com.example.A (2 usages)          |  // Searched FQN: ...     |
|   - com.example.B (3 usages)          |  // Project: ...          |
| > Project: guava (10 units)           |                           |
|                                       |  // Usage in: ...         |
|                                       |  // File: ...             |
|                                       |  <code snippet>           |
|                                       |                           |
+------------------------------------------------------------------+
|                                                        [Close]    |
+------------------------------------------------------------------+
```

- **Top panel**: Shows aggregate metrics (TP, FP, FN, P, R, F1) from the evaluation run.
- **Tabbed pane**: Switch between three categories: True Positives, False Positives, and False Negatives.
- **Left Tree**: A collapsible tree navigation grouped by project. Nodes show the number of units/usages.
- **Right panel**: Displays syntax-highlighted snippets for the selected code unit.
- **Bottom**: Close button to dismiss the dialog.

### Features

- **Project Grouping**: Results are organized in a tree structure by project for easier navigation in large datasets.
- **Result Categories**: Three distinct tabs allow for isolating different types of detection outcomes.
- **Syntax highlighting**: Code snippets are displayed with Java syntax highlighting using RSyntaxTextArea.
- **Selection-based preview**: Selecting a code unit in the tree instantly loads all associated usage snippets.
- **Metadata display**: Each snippet includes comments showing the usage location (FQN) and file path.
- **Standalone operation**: Can be run independently without the full Brokk application.

---

## RmShadingEval (RefactoringMiner-shading evaluation)

A three-phase harness to evaluate whether masking detected refactorings from the review diff (RM-shading) helps or obscures information for reviewers.

### Purpose

1. **Phase 1 (Runner)**: For a dataset of commit ranges, produce both vanilla and RM-shaded diffs and write a corpus JSON.
2. **Phase 2 (Judge)**: For each corpus item, ask an LLM (e.g. Claude 5.2) which representation is more useful for a reviewer; record winner and reasoning.
3. **Phase 3 (Aggregate)**: Compute overall preference and breakdowns by refactoring count and diff size; support manual pattern review.

### Dataset format

Create a JSON file with commit ranges for the repo you will run against:

```json
{
  "entries": [
    { "id": "pr-1", "fromRef": "merge-base-sha", "toRef": "HEAD" },
    { "id": "pr-2", "fromRef": "abc123", "toRef": "def456" }
  ]
}
```

- `id`: Unique identifier for the item.
- `fromRef`: Base ref (e.g. merge base, or `HEAD~1`).
- `toRef`: Target ref (e.g. `HEAD`, or `WORKING` for uncommitted changes).

### Usage

All commands are run from the **project root**. Paths like `build/reports/rm-shading-eval` and `app/src/test/resources/...` are relative to the root.

```bash
# Phase 1: Generate diff corpus (run first; wait for it to finish).
# RefactoringMiner is CPU-heavy: 40 entries can take 3–6+ hours. Use --limit for a quick test.
./gradlew :app:runRmShadingEvalRunner -Pargs="--dataset app/src/test/resources/rm-shading-eval/sample-dataset.json --output build/reports/rm-shading-eval"

# Phase 1 quick run (e.g. first 5 entries only, ~20–40 min depending on diff size).
# Note: --limit must be inside -Pargs="..."; options outside -Pargs are passed to Gradle, not the Runner.
./gradlew :app:runRmShadingEvalRunner -Pargs="--dataset app/src/test/resources/rm-shading-eval/sample-dataset.json --output build/reports/rm-shading-eval --limit 5"

# Phase 2: Run judge with OpenAI (GPT-5.2 recommended for this eval). Set OPENAI_API_KEY or pass --openai-api-key.
export OPENAI_API_KEY="sk-..."
./gradlew :app:runRmShadingEvalJudge -Pargs="--corpus build/reports/rm-shading-eval/corpus.json --output build/reports/rm-shading-eval"

# Or pass the key on the command line (no --project-dir needed when using OpenAI)
./gradlew :app:runRmShadingEvalJudge -Pargs="--corpus build/reports/rm-shading-eval/corpus.json --output build/reports/rm-shading-eval --openai-api-key sk-..."

# Phase 2: Test with one item only
./gradlew :app:runRmShadingEvalJudge -Pargs="--corpus build/reports/rm-shading-eval/corpus.json --output build/reports/rm-shading-eval --limit 1"

# Phase 3: Aggregate and print breakdowns
./gradlew :app:runRmShadingEvalAggregate -Pargs="--corpus-with-judgments build/reports/rm-shading-eval/corpus-with-judgments.json"
```

If you run Phase 2 without setting `OPENAI_API_KEY`, the Judge falls back to Brokk’s configured model (see Configuration below); for this eval, **OpenAI GPT-5.2 is the intended judge**.

### Output files

- **Phase 1**: `corpus.json` – one entry per dataset item with `vanillaDiff`, `rmShadedDiff`, `refactoringSummary`, counts.
- **Phase 2**: `corpus-with-judgments.json` (corpus + `winner`, `reasoning`), `summary.json` (counts for A / B / TIE).
- **Phase 3**: Console output only (overall rates and breakdown by refactoring count and diff size).

### Configuration and behavior

- **Judge = OpenAI (GPT-5.2), recommended**: For this eval the judge is meant to be an external LLM comparing the two diff representations. Use **OpenAI** (e.g. GPT-5.2): set the `OPENAI_API_KEY` environment variable or pass `--openai-api-key sk-...`. The Judge then uses the OpenAI Java SDK; `--project-dir` is not required. Optionally set `--openai-model` (default: `gpt-5.2`).

- **Brokk as judge (fallback)**: If you do *not* set `OPENAI_API_KEY` or `--openai-api-key`, the Judge uses Brokk’s config: whatever model is configured as ARCHITECT in Brokk, called via Brokk’s proxy. That means “Brokk as judge” = the same model and API you use in the Brokk app (e.g. Claude via Brokk). For the eval harness, **OpenAI GPT-5.2 is the intended judge**; Brokk is only a fallback.

- **Single-item test**: Pass `--limit 1` (or `--limit N`) to process only the first N corpus items. Useful to verify setup before running the full corpus.

- **Guided Review**: The eval does **not** run full Guided Review (Overview, Key Changes, Design Notes, etc.). It only compares two diff representations (vanilla vs RM-shaded) and asks the LLM which is more useful for a reviewer.

- **How commit ranges are loaded**: All data comes from your **local Git clone**. The Runner resolves `fromRef` and `toRef` with JGit and reads file contents from the repo’s object database. Nothing is fetched from GitHub or any remote; ensure the refs in your dataset exist in the clone (e.g. run a fetch first if the dataset uses branch names or PR refs).

- **Progress and ETA**: The Runner and Judge print one line per item with `[k/n]` and, after the first few items, an estimated time remaining (e.g. `est. 2 min remaining`).

- **Phase 1 duration**: RefactoringMiner runs AST/UML diff per commit range and is CPU-intensive. With 40 entries and large diffs, Phase 1 can take **3–6+ hours**. Use `--limit 5` (or similar) to process only the first N entries for a quick pipeline test; then run without `--limit` for the full eval.

- **RefactoringMiner failures**: On some commits RefactoringMiner can throw internally (e.g. NPE in extract-method detection). The Runner catches this, logs a short “falling back to text diff” message, and continues with the full vanilla diff for that item (`refactoringCount=0`). You may also see the library log “Ignored revision … due to error”; both are expected and harmless.

### Aggregate with reasonings export and reasoning summarizer

After Phase 3 you can export reasonings and have an LLM summarize them into themes for presentation:

```bash
# 1. Run aggregate (prints A/B/TIE and breakdowns)
./gradlew :app:runRmShadingEvalAggregate -Pargs="--corpus-with-judgments build/reports/rm-shading-eval-2/corpus-with-judgments.json"

# 2. Summarize reasonings into themes (requires OPENAI_API_KEY; writes reasoning-summary.md)
# Use the same corpus-with-judgments file—no need to re-run Judge or export a separate reasonings file.
./gradlew :app:runRmShadingEvalSummarizeReasonings -Pargs="--corpus-with-judgments build/reports/rm-shading-eval-2/corpus-with-judgments.json"
```

Optional: aggregate can write a reasonings-only JSON with `--write-reasonings <path>` if you want to run the summarizer with `--reasonings <path>` instead of `--corpus-with-judgments`.

The summarizer calls an LLM (default: gpt-4o-mini) to produce a short structured report: overall takeaway, why A was preferred, why B was preferred (if any), why TIE, and caveats. Use the generated `reasoning-summary.md` plus the aggregate console output when presenting to stakeholders.

### What to present to your boss

**Quick commands for token savings + surgical (by refactoring type) info:**

```bash
./gradlew :app:runRmShadingEvalAggregate -Pargs="--corpus-with-judgments build/reports/rm-shading-eval-2/corpus-with-judgments.json"
./gradlew :app:runRmShadingEvalReport -Pargs="--input build/reports/rm-shading-eval-2/corpus-with-judgments.json"
```

1. **Headline**: e.g. "On 40 Brokk merge commits, the judge (GPT-5.2) preferred the **full diff (A) in 87.5%** of cases; RM-shaded (B) in 7.5%, tie in 5%."
2. **Token savings**: Use the Report output—total and % tokens saved by RM-shading. If there’s no real savings, that undercuts the value.
3. **Breakdown**: Use the aggregate output (by refactoring count and by diff size). Example: "Even when there were 6+ refactorings, vanilla still won 30/33; B won only 3 times, all in the >1k-line bucket."
4. **Surgical (by refactoring type)**: Use the Report’s “By refactoring type” table to say which refactor types (if any) correlate with B winning vs A winning.
5. **When B won**: Use the "When B (RM-shaded) won" lines from the aggregate (id, refactoring count, vanilla lines) so they can see the handful of cases where masking helped.
6. **Themes**: Run the reasoning summarizer (steps above) and attach or paste **reasoning-summary.md** so they get a narrative of *why* (e.g. "refactorings often mixed with behavior changes", "full context needed for issue-spotting").
7. **Recommendation**: e.g. "Recommendation: keep RM-shading off by default for now; consider making it optional or enabling only when refactoring count is very high and diff is huge."

### Token and refactoring-type report (no re-run, safe for large files)

To answer **“Are we actually saving tokens?”** and **“Are some refactor types destroying information?”** run the Report tool. It **streams** the JSON (does not load the whole file into memory), so it’s safe for large corpus files (e.g. 118 MB).

```bash
# Token counts + A/B/TIE breakdown by refactoring type (use corpus-with-judgments so we have winner)
./gradlew :app:runRmShadingEvalReport -Pargs="--input build/reports/rm-shading-eval-2/corpus-with-judgments.json"
```

- **Token summary**: Total vanilla vs RM-shaded tokens (approximate, OpenAI-style), total saved and %, per-item median. If RM-shaded isn’t saving meaningful tokens, the report makes that obvious.
- **By refactoring type**: For each refactoring type present in the corpus (e.g. “Rename Variable”, “Extract Method”), how often the judge preferred A / B / TIE when that type was present. Use this to see if certain refactor types correlate with “B won” or “A won” (surgical view).

You can pass either `corpus.json` or `corpus-with-judgments.json`. For the by-type breakdown you need judgments (winner), so use `corpus-with-judgments.json`. For token-only stats, either file works.

### Pattern report (Phase 3 follow-up)

After aggregation, review a sample of `reasoning` strings in `corpus-with-judgments.json` (e.g. 20–30 items) and note recurring themes, or run the **reasoning summarizer** (see above) to get an LLM-generated theme summary. Then document findings in a short report and decide: keep RM-shading as default, make it optional, or refine which refactoring types to mask.
