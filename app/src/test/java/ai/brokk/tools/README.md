# Brokk Test Tools

This package contains command-line benchmark and evaluation tools for testing Brokk's analyzer capabilities.

## SearchFileContentsBenchmark

A CLI benchmark tool that compares `SearchTools.searchFileContents` against `rg` on a real project.

### Purpose

Use this benchmark to establish baseline search performance and track improvements to Brokk's grep-like fallback.

### Usage

Run via Gradle:

```bash
# Show help
./gradlew :app:runSearchFileContentsBenchmark -Pargs="--help"

# Benchmark this repository
./gradlew :app:runSearchFileContentsBenchmark -Pargs="--project /path/to/brokk"
```

### CLI Options

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `--project` | Yes | - | Path to project root to benchmark |
| `--glob` | No | `**/*.java` | File glob for both internal search and rg |
| `--warm-up-iterations` | No | `1` | Warm-up iterations per scenario |
| `--iterations` | No | `5` | Measured iterations per scenario |
| `--max-files` | No | `50` | `maxFiles` passed to `searchFileContents` |
| `--matches-per-file` | No | `10` | `matchesPerFile` for internal and `rg -m` |
| `--context-lines` | No | `0` | `contextLines` passed to `searchFileContents` |

### Notes

- `rg` must be installed and available on `PATH`; the benchmark fails fast otherwise.
- Built-in scenarios include miss/sparse-hit/dense-hit literal patterns.

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
```
