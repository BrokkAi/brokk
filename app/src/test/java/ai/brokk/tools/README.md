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

The tool writes three JSON files to the output directory:

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

2. **`true-positives.json`** - Correctly detected usages grouped by searched code unit:
   ```json
   {
     "codeUnits": [
       {
         "searchedFqn": "com.example.MyClass.myMethod",
         "project": "commons-lang",
         "language": "JAVA",
         "fqNames": ["com.example.Caller.call", "com.example.Other.use"]
       }
     ]
   }
   ```

3. **`false-positives.json`** - Incorrectly detected usages (same format as true-positives)

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
```
