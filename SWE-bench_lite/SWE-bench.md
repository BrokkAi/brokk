# SWE-bench-lite Evaluation with Brokk CLI

This document explains how to set up and run Brokk CLI against the SWE-bench-lite dataset for automated code generation evaluation.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Repository Setup](#repository-setup)
- [Running Evaluations](#running-evaluations)
- [Understanding the Results](#understanding-the-results)
- [Troubleshooting](#troubleshooting)
- [Advanced Usage](#advanced-usage)

## Overview

SWE-bench-lite is a benchmark for evaluating code generation models on real-world software engineering tasks. This setup allows you to:

1. **Clone Real Repositories**: Download actual GitHub repositories for each problem
2. **Checkout Specific Commits**: Work with the exact code state when the issue was reported
3. **Run Brokk CLI**: Use Brokk's AI agents to solve the problems
4. **Generate Patches**: Capture actual code changes as git diffs
5. **Submit Results**: Generate `preds.json` and `results.json` files for PR submission to the SWE-bench repository

### Key Components

- **`repo_setup.py`**: Clones repositories and sets up the evaluation environment
- **`evaluate_brokk.py`**: Runs Brokk CLI on each problem and captures results
- **`requirements.txt`**: Python dependencies for the evaluation scripts
- **Brokk CLI**: The AI-powered code analysis and modification tool

## Prerequisites

### System Requirements

- **Java 21+**: Required for Brokk CLI
- **Python 3.8+**: For evaluation scripts
- **Git**: For repository cloning and diff generation
- **Gradle**: For building Brokk CLI
- **8GB+ RAM**: Recommended for large repositories

### Brokk CLI Setup

1. **Build Brokk CLI**:
   ```bash
   cd /path/to/brokk
   ./gradlew shadowJar
   ```

2. **Verify Installation**:
   ```bash
   ./cli --help
   ```

3. **Configure Brokk**: Set up API keys through the Brokk GUI:
   ```bash
   # Start Brokk GUI to configure API keys
   ./cli --gui
   # Or use the GUI application directly
   java -jar app/build/libs/brokk-*.jar
   ```
   
   **Note**: API keys are configured through the Brokk GUI interface, not environment variables.

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

3. **Test Your Setup**:
   ```bash
   # Run the setup test script to verify everything is working
   python3 SWE-bench_lite/test_setup.py
   ```

## Quick Start

### 1. Set Up Repositories

```bash
# Clone repositories for first 5 test instances
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 5 --repos_dir swe_bench_repos
```

This will:
- Load the SWE-bench-lite dataset
- Clone GitHub repositories for each instance
- Checkout the specific commits mentioned in the dataset
- Create a mapping file (`instance_to_repo_mapping.json`)

### 2. Run Evaluation

```bash
# Evaluate on the first 3 instances
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 3 --repos_dir swe_bench_repos
```

This will:
- Run Brokk CLI on each repository with the problem statement
- Capture git diffs as model patches
- Generate a `preds.json` file in SWE-bench format
- Provide detailed progress updates

### 3. View Results

```bash
# Check the generated predictions
cat preds.json
```

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
# Set up repositories for test split
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 10 --repos_dir swe_bench_repos

# Set up repositories for dev split
python3 SWE-bench_lite/repo_setup.py --split dev --repos_dir dev_repos

# Set up specific number of repositories
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 5 --repos_dir swe_bench_repos
```

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

### Basic Evaluation

```bash
# Evaluate all available instances
python3 SWE-bench_lite/evaluate_brokk.py --split test --repos_dir swe_bench_repos

# Evaluate specific number of instances
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 5 --repos_dir swe_bench_repos

# Evaluate specific instance
python3 SWE-bench_lite/evaluate_brokk.py --instance_id matplotlib__matplotlib-18869 --repos_dir swe_bench_repos
```

### Advanced Options

```bash
# Custom model name and output file
python3 SWE-bench_lite/evaluate_brokk.py \
    --split test \
    --max_instances 3 \
    --model_name "brokk-cli-v1.0" \
    --output_file "my_predictions.json" \
    --repos_dir swe_bench_repos

# Enable verbose logging
python3 SWE-bench_lite/evaluate_brokk.py \
    --split test \
    --max_instances 2 \
    --verbose \
    --repos_dir swe_bench_repos
```

### Evaluation Process

For each instance, the script:

1. **Loads Problem Statement**: Gets the issue description from SWE-bench-lite
2. **Changes to Repository Directory**: Navigates to the cloned repository
3. **Records Initial State**: Captures the initial git commit and status
4. **Runs Brokk CLI**: Executes `./cli --project . --deepscan --code "problem statement"`
5. **Captures Changes**: Detects any modifications made by Brokk
6. **Generates Patch**: Creates a git diff of the changes
7. **Records Results**: Saves success/failure status and patch

### Real-time Status Updates

The evaluation provides detailed progress tracking:

```
[09:01:10] ℹ️  Loading SWE-bench-lite dataset from SWE-bench_lite
[09:01:10] ✅ Loaded dataset with 23 dev instances and 300 test instances
[09:01:10] ✅ Loaded repository mapping with 5 entries
[09:01:10] ℹ️  Found 5 instances with available repositories
[09:01:10] ℹ️  Starting evaluation of 1 instances
[09:01:10] ℹ️  Model name: brokk-cli

============================================================
Instance 1/1: matplotlib__matplotlib-18869
============================================================
[09:01:10] ℹ️  Running Brokk CLI on matplotlib__matplotlib-18869
[09:01:10] ℹ️  Repository: swe_bench_repos/matplotlib__matplotlib_matplotlib__matplotlib-18869
[09:01:10] ℹ️  Problem: Add easily comparable version info to toplevel
[09:01:10] ℹ️  Checking initial git status...
[09:01:10] ℹ️  Initial commit: b7d05919865fc0c37a0164cf467d5d5513bd0ede
[09:01:10] ℹ️  Starting Brokk CLI execution...
[09:01:10] 🔄 Brokk CLI is analyzing the problem and generating code...
```

## Understanding the Results

### Output Files

The evaluation generates two key files for SWE-bench submission:

#### 1. `preds.json` - Model Predictions

Contains the actual code patches generated by Brokk:

```json
{
  "matplotlib__matplotlib-18869": {
    "model_name_or_path": "brokk-cli",
    "instance_id": "matplotlib__matplotlib-18869",
    "model_patch": "diff --git a/lib/matplotlib/__init__.py b/lib/matplotlib/__init__.py\nindex 1234567..abcdefg 100644\n--- a/lib/matplotlib/__init__.py\n+++ b/lib/matplotlib/__init__.py\n@@ -10,6 +10,7 @@ import warnings\n \n __version__ = '3.7.0'\n+version_info = (3, 7, 0)\n \n # ... rest of patch"
  }
}
```

#### 2. `results.json` - Evaluation Results

Contains detailed evaluation metrics and metadata (generated by SWE-bench evaluation tools).

### Submission Process

To submit your results to SWE-bench:

1. **Generate Results**: Run the evaluation to create `preds.json`
2. **Evaluate Patches**: Use SWE-bench evaluation tools to generate `results.json`
3. **Create Pull Request**: Submit both files as a PR to the SWE-bench repository
4. **Review Process**: Your submission will be reviewed and merged if valid

### Interpreting Results

- **Empty Patch (`""`)**: Brokk ran but didn't make changes (either couldn't solve or no solution needed)
- **Non-empty Patch**: Brokk made actual code changes
- **Failed Evaluation**: Brokk CLI encountered an error or timeout

### Success Metrics

- **Successful Evaluations**: Number of instances where Brokk CLI completed without errors
- **Changes Made**: Number of instances where Brokk actually modified code
- **Success Rate**: Percentage of successful evaluations
- **Execution Time**: Time taken for each evaluation

## Troubleshooting

### Common Issues

#### 1. "No repository mapping found"

**Problem**: The evaluation script can't find the repository mapping file.

**Solution**: 
```bash
# Make sure you ran repo_setup.py first
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 5 --repos_dir swe_bench_repos
```

#### 2. "Brokk CLI execution timed out"

**Problem**: Brokk CLI is hanging and not responding.

**Most Common Cause**: Brokk API keys not configured through the GUI.

**Solutions**:

1. **Configure API keys through Brokk GUI**:
   ```bash
   # Start Brokk GUI to configure API keys
   ./cli --gui
   # Or use the GUI application directly
   java -jar app/build/libs/brokk-*.jar
   ```

2. **Test Brokk CLI with configured keys**:
   ```bash
   # This should work without hanging after GUI configuration
   cd /path/to/simple/project
   ./cli --ask "What is this project?"
   ```

3. **Check Brokk configuration**:
```bash
   # Verify Brokk CLI can start
   ./cli --help
```

**Other Possible Causes**:
- Large repository causing memory issues
- Network connectivity issues for AI model calls
- Model rate limiting

#### 2a. "Brokk CLI appears to hang with no logs"

**What we added**:
- The evaluator now streams Brokk's stdout/stderr live and kills Brokk after inactivity.
- Java CLI prints explicit phase markers: "[BROKK] PHASE: ..." for Deep Scan and Action.

**Controls/Knobs**:
- `BROKK_INACTIVITY_TIMEOUT` (seconds). Default 180. Increase for large repos.
  ```bash
  export BROKK_INACTIVITY_TIMEOUT=480
  python3 SWE-bench_lite/evaluate_brokk.py --instance_id sympy__sympy-22005 --repos_dir swe_bench_repos
  ```

**What to look for**:
- Last printed phase marker tells where it stalled: Deep Scan vs Action.
- Live stderr often shows model/network errors otherwise swallowed.

**If killed by inactivity watchdog**:
- Re-run with a higher timeout for heavy repos.
- Verify model/API keys and network connectivity.
- Try running Brokk directly in the repo:
  ```bash
  cd swe_bench_repos/<repo>
  ../../cli --project . --deepscan --code "<problem>"
  ```

**Debug Steps**:
```bash
# Check if Brokk CLI works on a simple case
cd /path/to/simple/project
timeout 30 ./cli --ask "What is this project?"

# Check Brokk CLI configuration
./cli --help
```

#### 3. "Repository has uncommitted changes"

**Problem**: The repository has modifications before Brokk runs.

**Solution**: This is usually just the `.brokk/` directory created by previous runs. It's safe to ignore.

#### 4. "No changes detected"

**Problem**: Brokk CLI completed but didn't modify any files.

**Possible Causes**:
- The problem doesn't require code changes
- Brokk couldn't understand the problem statement
- The solution was already implemented

**Investigation**:
```bash
# Check what Brokk CLI actually did
cd swe_bench_repos/matplotlib__matplotlib_matplotlib__matplotlib-18869
git log --oneline -5
git status
```

#### 5. "Dataset not found"

**Problem**: SWE-bench-lite dataset directory is missing.

**Solution**:
```bash
# Make sure you have the SWE-bench_lite directory
ls -la SWE-bench_lite/
# Should contain: dev/, test/, dataset_dict.json
```

### Debugging Tips

#### Enable Verbose Logging

```bash
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 1 --verbose --repos_dir swe_bench_repos
```

#### Test Individual Components

```bash
# Test repository setup
python3 SWE-bench_lite/repo_setup.py --demo

# Test Brokk CLI directly
cd swe_bench_repos/matplotlib__matplotlib_matplotlib__matplotlib-18869
../../cli --project . --ask "What is this project?"

# Test evaluation on single instance
python3 SWE-bench_lite/evaluate_brokk.py --instance_id matplotlib__matplotlib-18869 --repos_dir swe_bench_repos
```

#### Check Repository State

```bash
# Before evaluation
cd swe_bench_repos/matplotlib__matplotlib_matplotlib__matplotlib-18869
git status
git log --oneline -3

# After evaluation
git status
git diff
git log --oneline -3
```

## SWE-bench Submission Workflow

### Complete Evaluation Process

The full SWE-bench evaluation process involves several steps:

1. **Generate Predictions**: Use this setup to create `preds.json`
2. **Evaluate Patches**: Use SWE-bench evaluation tools to generate `results.json`
3. **Submit Results**: Create a PR to the SWE-bench repository

### Step-by-Step Submission

#### 1. Generate Predictions

```bash
# Set up repositories
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 10 --repos_dir swe_bench_repos

# Run evaluation
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 10 --repos_dir swe_bench_repos

# This creates preds.json
```

#### 2. Evaluate Patches

Use the official SWE-bench evaluation tools to generate `results.json`:

```bash
# Install SWE-bench evaluation tools
pip install swe-bench

# Evaluate your predictions
swe-bench-eval --predictions_path preds.json --log_dir results/
```

#### 3. Submit to SWE-bench

1. **Fork the SWE-bench repository**: https://github.com/princeton-nlp/SWE-bench
2. **Create a new branch**: `git checkout -b brokk-submission`
3. **Add your files**:
   ```bash
   git add preds.json results.json
   git commit -m "Add Brokk CLI evaluation results"
   ```
4. **Create Pull Request**: Submit PR to the main SWE-bench repository
5. **Review Process**: Your submission will be reviewed and merged if valid

### Required Files for Submission

- **`preds.json`**: Generated by `evaluate_brokk.py`
- **`results.json`**: Generated by SWE-bench evaluation tools
- **Model metadata**: Include model name, version, and configuration details

## Advanced Usage

### Custom Evaluation Scripts

You can create custom evaluation scripts by importing the core functions:

```python
from evaluate_brokk import load_swe_bench_lite_dataset, run_brokk_cli, evaluate_instances

# Load dataset
dataset = load_swe_bench_lite_dataset("SWE-bench_lite")
instances = list(dataset["test"])[:5]  # First 5 test instances

# Load repository mapping
import json
with open("swe_bench_repos/instance_to_repo_mapping.json") as f:
    repo_mapping = json.load(f)

# Run evaluation
results = evaluate_instances(instances, repo_mapping, model_name="my-brokk-model")
```

### Batch Processing

```bash
#!/bin/bash
# Process multiple splits and configurations

for split in dev test; do
    echo "Processing $split split..."
    python3 SWE-bench_lite/evaluate_brokk.py \
        --split $split \
        --max_instances 10 \
        --model_name "brokk-cli-$split" \
        --output_file "preds_$split.json" \
        --repos_dir "swe_bench_repos"
done
```

### Performance Optimization

#### Memory Management

```bash
# For large repositories, increase JVM heap size
export JAVA_OPTS="-Xmx8G -Xms4G"
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 10
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
│   ├── preds.json         # Generated predictions
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

## Contributing

To improve the SWE-bench-lite evaluation:

1. **Report Issues**: Document any problems with specific instances
2. **Improve Error Handling**: Add better error messages and recovery
3. **Add Metrics**: Implement additional evaluation metrics
4. **Optimize Performance**: Reduce evaluation time and memory usage
---

For more information about Brokk CLI itself, see the main project documentation.