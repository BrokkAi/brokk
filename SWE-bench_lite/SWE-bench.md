# SWE-bench-lite Evaluation with Brokk CLI

This document explains how to set up and run Brokk CLI against the SWE-bench-lite dataset for automated code generation evaluation.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Repository Setup](#repository-setup)
- [Running Evaluations](#running-evaluations)
- [Understanding the Results](#understanding-the-results)
- [Advanced Usage](#advanced-usage)

## Overview

SWE-bench-lite is a benchmark for evaluating code generation models on real-world software engineering tasks. This setup allows you to:

1. **Clone Real Repositories**: Download repositories for each problem
2. **Checkout Specific Commits**: Work with the exact code state when the issue was reported
3. **Run Brokk CLI**: Use Brokk via the CLI (BrokkCli) to solve the problems
4. **Generate Patches**: Capture code changes as git diffs (ignores hidden folders like `.brokk`)
5. **Submit Results**: Generate `preds.json` and `results.json` files for PR submission to the SWE-bench repository. Also generate diagnostics.json for timestamp, machine resource usage, average/min/max problem solution times.

### Key Components

- **`repo_setup.py`**: Clones repositories and sets up the evaluation environment
- **`evaluate_brokk.py`**: Runs Brokk CLI on each problem and captures results
- **`requirements.txt`**: Python dependencies for the setup and evaluation scripts
- **Brokk CLI**: How we interface with Brokk programmatically. This is somewhat inefficient because it has to be loaded up each time the `./cli ...` command is run.
- **`reset_repos.py`**: Reset each repository so that `evaluate_brokk.py` can be run again. Note that changes are actual changes to the codebase folder under `swe_bench_repos/specific_project_pid` so it's not like it's all in memory. Hence, the disk files must be reset. Note that `evaluate_brokk.py` does this automatically, but this script is here if desired.

## Prerequisites

### System Requirements

- **Java 21+**: Required for Brokk CLI
- **Python 3.8+**: For evaluation scripts and setting up repos
- **Git**: For repository cloning and diff generation
- **Gradle**: For building Brokk CLI
- **16GB+ RAM**: Recommended for large repositories
- **Network connection**: ideally fast/wired, there's a large amount of data that needs to be set up. As there's a lot of Django issues, you might think this requires only one repo, but the program has to fetch a specific commit for each, meaning you end up with one unique folder one per issue/problem. This essentially means 300 (if you do the full Verified dataset, not limiting it by `--max_repos 20` for instance) full repositories. You'll need some disk space too (TODO: specify how much)
- **Linux**: Not tested on Windows/Max, so use at your own risk

### Brokk CLI Setup

1. **Build Brokk CLI**:
   ```bash
   cd /path/to/brokk
   ./gradlew shadowJar
   ```
   A shadow JAR is needed to run the CLI! `./gradlew build` won't generate one.

2. **Verify Installation**:
   ```bash
   ./cli --help
   ```

3. **Configure Brokk**: Set up API keys, preferred models, etc.


### Python Environment Setup

1. **Create Virtual Environment**:
   ```bash
   python3 -m venv .venv
   source .venv/bin/activate  # On Windows: .venv\Scripts\activate
   ```

2. **Install Dependencies**:
   ```bash
   pip install -r SWE-bench_lite/requirements.txt
   ```

## Quick Start

### 1. Set Up Repositories

```bash
# Clone repositories for first 5 test instances (default: 5 parallel workers)
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 5 --repos_dir swe_bench_repos

# Fast cloning with 10 parallel workers (MUCH faster!). also only sets up 20 repos via the max_repos argument. Not specified = all of them.
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 20 --max_workers 10 --repos_dir swe_bench_repos
```

If you want to clone the whole dataset of 300 repos, do not specify a `--max_repos` kwarg.

This will:
- Load the SWE-bench-lite dataset
- Clone GitHub repositories **in parallel** for much faster setup
- Checkout the specific commits mentioned in the dataset
- Create a mapping file (`instance_to_repo_mapping.json`) -- this is very important to have! Is created at the end of `repo_setup.py`'s execution.

**Performance Tip**: Use `--max_workers 10` or higher for much faster cloning (5-10x speedup on good connections)!

### 2. Run Evaluation

```bash
# Quick test on 3 instances
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 3 --repos_dir swe_bench_repos

# Recommended: Fast, parallel evaluation with code agent
python3 SWE-bench_lite/evaluate_brokk.py --split test --agent code --max_retries 2 --repos_dir swe_bench_repos --max-workers 5
```

This will:
- Run Brokk CLI with your chosen agent (code/lutz/architect)
- Gather context using Deep Scan or autonomous research
- Generate code changes to solve the problem
- Capture git diffs as model patches (excluding `.brokk/` folder)
- Generate `preds.json`, `results.json`, and `diagnostics.json` in SWE-bench format
- Save checkpoints for resumability

### 3. View Results

```bash
# Check the generated predictions
cat swe_bench_tests/XXX_date_time_model_folder/preds.json
```
(`results.json` isn't very interesting to look at, just success statistics)

## Repository Setup

The `repo_setup.py` script handles the complex process of setting up real repositories for evaluation.

### What It Does

1. **Loads SWE-bench-lite Dataset**: Reads the problem descriptions and metadata
2. **Clones GitHub Repositories**: Downloads the actual source code
3. **Checks Out Specific Commits**: Uses the exact code state when issues were reported
4. **Creates Safe Directory Names**: Handles repository naming conflicts
5. **Generates Mapping File**: Creates `instance_to_repo_mapping.json` for evaluation

### Usage Examples

```bash
# Set up repositories for test split (default: 5 workers)
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 10 --repos_dir swe_bench_repos

# Fast setup with 15 parallel workers
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 50 --max_workers 15 --repos_dir swe_bench_repos

# Set up all dev repositories with maximum parallelism
python3 SWE-bench_lite/repo_setup.py --split dev --max_workers 20 --repos_dir dev_repos

# Conservative setup for slower connections
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 5 --max_workers 2 --repos_dir swe_bench_repos
```

### Parallel Cloning Performance

The setup script uses **parallel cloning** with ThreadPoolExecutor for massive speedup.

**Recommendation**: Start with `--max_workers 10` and adjust based on your network speed and system resources.

### Output Structure

After running `repo_setup.py`, you'll have:

```
swe_bench_repos/
├── instance_to_repo_mapping.json
├── django__django_django__django-15213/
├── matplotlib__matplotlib_matplotlib__matplotlib-18869/
└── ...
```

The `instance_to_repo_mapping.json` file maps each instance ID to its repository path:

```json
{
  "django__django-15213": "swe_bench_repos/django__django_django__django-15213",
  "matplotlib__matplotlib-18869": "swe_bench_repos/matplotlib__matplotlib_matplotlib__matplotlib-18869"
}
```

## Running Evaluations

The `evaluate_brokk.py` script orchestrates the evaluation process.

### Command-Line Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--dataset_path` | string | `SWE-bench_lite` | Path to SWE-bench-lite dataset directory -- should contain dev & test folders from HF |
| `--split` | choice | `test` | Dataset split to evaluate (`dev` or `test`) |
| `--repos_dir` | string | `swe_bench_repos` | Directory containing cloned repositories |
| `--max_instances` | int | None | Maximum number of instances to evaluate (omit for all) |
| `--instance_id` | string | None | Specific instance ID to evaluate (e.g., `django__django-15213`), used for a single test |
| `--model_name` | string | `brokk-cli` | Model name for output files |
| `--output_dir` | string | auto | Output directory (default: `swe_bench_tests/{timestamp}_{model}`) |
| `--agent` | choice | `lutz` | Brokk agent: `lutz` (research+tasks), `code` (fast), `architect` (planning) |
| `--max_retries` | int | `1` | Max retry attempts when Brokk requests more files |
| `--max-workers` | int | `1` | Number of parallel workers (1=sequential, 2-5 recommended) |
| `--no-reset` | flag | False | Don't reset repos to clean state before evaluation |
| `--resume` | flag | False | Auto-resume from checkpoint (non-interactive), requires specified output_dir from previous test! |
| `--force-fresh` | flag | False | Ignore existing checkpoint and start fresh |
| `--quiet` | flag | False | Show only tqdm progress bar, suppress all other output |
| `--verbose` | flag | False | Enable verbose logging |

### Common Usage Examples

**Recommended production command (fast, reliable, resumable):**
```bash
python3 SWE-bench_lite/evaluate_brokk.py \
    --split test \
    --agent code \
    --max_retries 2 \
    --repos_dir swe_bench_repos \
    --max-workers 5
```

**Quick test on a few instances:**
```bash
# Test on first 3 instances
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 3 --repos_dir swe_bench_repos
```

**Full evaluation with best quality (slower but more autonomous):**
```bash
# Uses lutz agent (default) for thorough research and planning
python3 SWE-bench_lite/evaluate_brokk.py --split test --repos_dir swe_bench_repos
```

**Fastest evaluation (parallel + code agent):**
```bash
# Maximize speed with parallel workers and fast agent
python3 SWE-bench_lite/evaluate_brokk.py \
    --split test \
    --agent code \
    --max-workers 5 \
    --quiet \
    --repos_dir swe_bench_repos
```

**Resume interrupted evaluation:**
```bash
# Automatically resume from checkpoint without prompts
python3 SWE-bench_lite/evaluate_brokk.py \
    --split test \
    --agent code \
    --max_retries 2 \
    --repos_dir swe_bench_repos \
    --output_dir swe_bench_tests/20250113_143022_brokk-cli \
    --resume
```

**Evaluate single instance for debugging:**
```bash
python3 SWE-bench_lite/evaluate_brokk.py \
    --instance_id django__django-15213 \
    --repos_dir swe_bench_repos \
    --verbose
```

### Agent Comparison

| Agent | Speed | Autonomy | Best For | Context Strategy |
|-------|-------|----------|----------|------------------|
| **lutz** (default) | Slow (10-15 min) | High | Complex problems, autonomous research | Built-in research (no Deep Scan) |
| **code** | Fast (3-5 min) | Medium | Simple fixes, quick iterations | Deep Scan + retry with requested

**Recommendation**: Use `code` agent with `--max-workers 5` for fastest results on most SWE-bench problems.

### Evaluation Process

For each instance, the script:

1. **Loads Problem Statement**: Gets the issue description from SWE-bench-lite
2. **Changes to Repository Directory**: Navigates to the cloned repository
3. **Sets Up Configuration**: Copies template `project.properties` to `.brokk/` directory
4. **Records Initial State**: Captures the initial git commit and status
5. **Runs Brokk CLI**: 
   - **lutz** agent (default): `./cli --project . --lutz "problem statement"` (built-in research, no Deep Scan)
   - **code** agent: `./cli --project . --deepscan --code "problem statement"` (uses Deep Scan for context)
6. **Automatic File Request Detection**:
   - If Brokk completes without changes but requests specific files
   - The script automatically detects requested file paths (scans for `\`is/valid/filepath/file.txt\``)
   - Retries with those files added via `--edit` flags
   - **Deep Scan is skipped on retries** (saves time and avoids token limits)
   - Repeats up to `--max_retries` times (default: 1)
7. **Captures Changes**: Detects any modifications made by Brokk
8. **Generates Patch**: Creates a git diff (excluding `.brokk/` directory)
9. **Records Results**: Saves success/failure status and patch

### Automatic File Request Retry

When running the `code` agent, the evaluation script automatically detects when Brokk requests additional files and retries with those files included. This happens when:

- Brokk completes successfully but makes no code changes
- The output contains phrases like "Please add these files" or "need to see the implementation"
- File paths are mentioned in backticks (e.g., \`django/core/files/storage.py\`)

**How it works:**

1. **Initial Run**: Brokk runs with `--deepscan` but no explicit files
2. **Detection**: Script parses stdout/stderr for file requests
3. **Extraction**: Finds file paths in backticks or numbered lists
4. **Validation**: Verifies files exist in the repository
5. **Retry**: Re-runs with `--edit path/to/file.py` for each requested file
6. **Repeat**: Can retry up to `--max_retries` times with newly requested files

**Configuration:**
- `--max_retries 0`: Disable automatic retries (just use deepscan)
- `--max_retries 1`: Allow one retry with requested files (default)
- `--max_retries 2`: Allow up to two retries for complex problems

**Performance Optimization:**
- **code/architect agents**: 
  - First attempt uses `--deepscan` to intelligently gather context
  - Retry attempts skip `--deepscan` and use only explicitly requested files
- **lutz agent**: 
  - Has built-in intelligent research phase (no `--deepscan` needed)
  - Retries still skip research and use only explicitly requested files
- This avoids token limit errors and speeds up retries significantly

**Practical advice**: Use `--agent code --max-workers 5` for fastest throughput on SWE-bench evaluations.

### Checkpoint and Resume Support

Evaluation runs are automatically checkpointed after each instance completes, so you **never lose progress** if the program crashes or you need to exit. This is crucial for long-running evaluations of 100+ instances.

#### How It Works

1. **Automatic Checkpointing**: After each instance completes, a `checkpoint.json` file is saved in the output directory
2. **Automatic Resume Detection**: When you re-run with the same output directory, you'll be prompted to resume
3. **Graceful Shutdown**: Press `Ctrl+C` to interrupt - checkpoint is saved before exit, handled across parallel
4. **Completion Cleanup**: Checkpoint file is automatically deleted when evaluation completes successfully

#### Resume Modes

**Non-Interactive Resume:**
```bash
# Automatically resume without prompting (good for scripts/cron jobs)
python3 SWE-bench_lite/evaluate_brokk.py --split test --agent code --max_retries 2 --repos_dir swe_bench_repos --max-workers 5 --output_dir my_run --resume
```

**Force Fresh Start:**
```bash
# Ignore existing checkpoint and start over
python3 SWE-bench_lite/evaluate_brokk.py --split test --agent code --max_retries 2 --repos_dir swe_bench_repos --max-workers 5 --output_dir my_run --force-fresh
```

#### Checkpoint File Contents

The checkpoint file (`checkpoint.json`) contains:
- List of completed instance IDs
- All results for completed instances (patches, errors, execution times)
- Evaluation start time (for accurate diagnostics)
- Last checkpoint timestamp

**Example checkpoint.json:**
```json
{
  "completed_instances": ["matplotlib__matplotlib-18869", "django__django-11099"],
  "results": {
    "matplotlib__matplotlib-18869": {
      "model_name_or_path": "brokk-cli",
      "instance_id": "matplotlib__matplotlib-18869",
      "model_patch": "diff --git...",
      "success": true,
      "execution_time": 45.2
    }
  },
  "eval_start_time": 1705154422.5,
  "last_checkpoint": "2025-01-13T14:30:22",
  "checkpoint_count": 2
}
```
## Understanding the Results

### Output Directory Structure

The evaluation creates a timestamped directory with all results:

```
swe_bench_tests/
└── 20250113_143022_brokk-cli/
    ├── preds.json          # Model predictions for SWE-bench evaluation
    ├── results.json        # Summary statistics (resolve/unresolved TBD until tests run)
    ├── diagnostics.json    # Performance metrics and system info
    └── checkpoints/        # Progress checkpoints (auto-deleted on completion)
        └── checkpoint.json
```

### Output Files

#### 1. `preds.json` - Model Predictions

Contains the actual code patches generated by Brokk in SWE-bench format:

```json
{
  "matplotlib__matplotlib-18869": {
    "model_name_or_path": "brokk-cli",
    "instance_id": "matplotlib__matplotlib-18869",
    "model_patch": "diff --git a/lib/matplotlib/__init__.py..."
  }
}
```

#### 2. `results.json` - Evaluation Summary

Contains summary statistics in SWE-bench format:

```json
{
  "total_instances": 300,
  "submitted_instances": 294,
  "completed_instances": 254,
  "resolved_instances": 0,
  "unresolved_instances": 254,
  "empty_patch_instances": 39,
  "error_instances": 1,
  "completed_ids": ["instance1", "instance2", ...],
  "empty_patch_ids": ["instance3", ...],
  "error_ids": ["instance4"],
  "schema_version": 2
}
```

**Note**: `resolved_instances` and `unresolved_instances` are unclear, set to 0/# of instances for now. May be changed upon submission given the team's analysis.

#### 3. `diagnostics.json` - Performance Metrics

Contains detailed performance and system information:

```json
{
  "evaluation_metadata": {
    "model_name": "brokk-cli",
    "start_time": "2025-01-13T14:30:22",
    "end_time": "2025-01-13T16:45:30",
    "total_instances": 300
  },
  "timing_statistics": {
    "total_runtime_seconds": 8108.5,
    "total_runtime_human": "2h 15m 8s",
    "average_runtime_per_instance_seconds": 27.02,
    "min_runtime_seconds": 12.5,
    "max_runtime_seconds": 185.3
  },
  "retry_statistics": {
    "instances_with_retries": 45,
    "total_retry_attempts": 52,
    "average_retries_per_instance": 0.17
  },
  "outcome_statistics": {
    "instances_with_code_changes": 254,
    "instances_no_changes": 39,
    "instances_failed": 1,
    "instances_empty_patch": 39
  },
  "system_info": {
    "memory_used_mb": 1250.5,
    "cpu_percent": 45.2,
    "python_version": "3.10.12",
    "platform": "linux"
  }
}
```

### Interpreting Results

- **Empty Patch (`""`)**: Brokk ran but didn't make changes (either couldn't solve or no solution needed)
- **Non-empty Patch**: Brokk made actual code changes
- **Failed Evaluation**: Brokk CLI encountered an error or timeout (10m timeout right now)

### Success Metrics

- **Successful Evaluations**: Number of instances where Brokk CLI completed without errors
- **Changes Made**: Number of instances where Brokk actually modified code
- **Success Rate**: Percentage of successful evaluations
- **Execution Time**: Time taken for each evaluation

## Advanced Usage

### Performance Optimization

**Maximize throughput:**
```bash
# Parallel execution with code agent is fastest
python3 SWE-bench_lite/evaluate_brokk.py \
    --split test \
    --agent code \
    --max_retries 2 \
    --max-workers 5 \
    --quiet \
    --repos_dir swe_bench_repos
```

**For large repositories (high memory usage):**
```bash
# Increase JVM heap size
export JAVA_OPTS="-Xmx8G -Xms4G"
python3 SWE-bench_lite/evaluate_brokk.py --split test --repos_dir swe_bench_repos
```

## File Structure

```
brokk/
├── cli                     # Brokk CLI script
├── SWE-bench_lite/
|   ├── dev/
|   ├── test/
|   ├── dataset_dict.json
│   ├── repo_setup.py      # Repository setup script
│   ├── evaluate_brokk.py  # Evaluation script
│   ├── test_setup.py      # Setup verification script
│   ├── requirements.txt   # Python dependencies
│   └── SWE-bench.md       # This documentation
├── swe_bench_repos/       # Cloned repositories
│   ├── instance_to_repo_mapping.json
│   ├── django__django_django__django-15213/
│   ├── matplotlib__matplotlib_matplotlib__matplotlib-18869/
│   └── ...
├── app/build/libs/
│   └── brokk-*.jar        # Shadow JAR with dependencies
├── docs/
│   └── litellm_config.yaml # AI model configuration
```

### Output Directory (`swe_bench_tests/`)

When running `evaluate_brokk.py`, all predictions and results are saved to a subdirectory under `swe_bench_tests/` by default. The structure is as follows:

- The directory is named automatically by timestamp and model name, for example:  
  `swe_bench_tests/20240605_120300_brokk-cli-test/`
- The following files are generated in the output directory:
    - `preds.json` – The model's patch predictions for each instance
    - `results.json` – Summary statistics and outcome for each instance
    - `diagnostics.json` – Runtime statistics and system information (memory, CPU, etc.)


Otherwise, results appear in `swe_bench_tests/{timestamp}_{model_name}/` by default.

**Example output directory structure:**
```
swe_bench_tests/
└── 20240605_120300_brokk-cli-test/
    ├── preds.json
    ├── results.json
    └── diagnostics.json
```

See the end of `evaluate_brokk.py` for summary and descriptions of each output file.

