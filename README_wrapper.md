# Brokk SWE-bench-Live Integration

This directory contains tools for integrating Brokk with SWE-bench-Live evaluation.

## Files

- `brokk_wrapper.py` - Main wrapper script for running Brokk CLI programmatically
- `swe_bench_helper.py` - Helper utilities for SWE-bench-Live dataset processing
- `README_wrapper.md` - This documentation

## Quick Start

### 1. Single Task Execution

Run Brokk on a single task and get the git diff:

```bash
# Basic usage - outputs diff to stdout
python brokk_wrapper.py "Fix the bug where users can't login"

# For SWE-bench format with instance ID
python brokk_wrapper.py "Fix the bug where users can't login" \
    --instance_id "myproject-123" \
    --output_file "prediction.json"

# Specify target project (if different from current directory)
python brokk_wrapper.py "Add error handling to the API" \
    --target_project "/path/to/target/project" \
    --instance_id "myproject-124" \
    --output_file "prediction.json"
```

### 2. Processing SWE-bench Datasets

Process an entire SWE-bench dataset:

```bash
# Process a dataset file
python swe_bench_helper.py process dataset.json /path/to/target/project \
    --output predictions.json

# Limit to first 10 instances for testing
python swe_bench_helper.py process dataset.json /path/to/target/project \
    --output predictions.json \
    --max_instances 10

# Use a specific model
python swe_bench_helper.py process dataset.json /path/to/target/project \
    --output predictions.json \
    --model "gpt-4"

# Process a local HuggingFace Arrow dataset (load_from_disk)
python swe_bench_helper.py process_hf /path/to/SWE-bench_lite \
    --auto_checkout --workspace_root ./workspace \
    --output predictions.json \
    --max_instances 10 \
    --log_dir run_logs
```

### 3. Validation

Validate your predictions file format:

```bash
python swe_bench_helper.py validate predictions.json
```

## Wrapper Options

The `brokk_wrapper.py` script supports these options:

- `instructions` - Natural language instructions for the code change (required)
- `--instance_id` - Instance ID for SWE-bench format output
- `--output_file` - File to save output (prints to stdout if not provided)
- `--target_project` - Path to target project (defaults to current directory)
- `--brokk_project` - Path to Brokk project root (auto-detected if not provided)
- `--model` - Override model to use for the code task
- `--dry_run` - Show what would be executed without running
- `--verbose` - Show detailed output including Brokk's stdout/stderr
- `--log_dir` - Directory to write logs (stdout, stderr, git diff, command)

For Arrow datasets (`process_hf`):
- `--auto_checkout` to automatically clone and checkout each instance's repo
- `--workspace_root` path that will contain per-instance repos
- Optional `--target_project` to run all instances against one local repo (when appropriate)

## SWE-bench Format

The wrapper outputs predictions in the standard SWE-bench format:

```json
{
  "instance_id": "myproject-123",
  "model_name_or_path": "BrokkAgent",
  "model_patch": "diff --git a/src/main.py b/src/main.py\nindex abc123..def456 100644\n--- a/src/main.py\n+++ b/src/main.py\n@@ -10,6 +10,8 @@ def login(username, password):\n     if not username or not password:\n+        raise ValueError(\"Username and password required\")\n     # ... rest of function"
}
```

## Requirements

- Python 3.8+
- Java 21+ (for running Brokk)
- Git repository for the target project
- Brokk project must be built (`./gradlew shadowJar`)

## Environment Setup

1. **Ensure Java 21+ is installed:**
   ```bash
   java -version
   ```

2. **Build Brokk if not already built:**
   ```bash
   ./gradlew shadowJar
   ```

3. **Verify git is available:**
   ```bash
   git --version
   ```

## Usage Patterns

### For SWE-bench-Live Evaluation

1. **Prepare your workspace:**
   ```bash
   # Clone the target repository
   git clone https://github.com/user/target-repo.git
   cd target-repo
   
   # Ensure it's a clean state
   git status
   ```

2. **Run evaluation:**
   ```bash
   # Single instance
   python /path/to/brokk_wrapper.py "Fix login validation bug" \
       --instance_id "target-repo-001" \
       --output_file "pred_001.json"
   
   # Full dataset
   python /path/to/swe_bench_helper.py process dataset.json . \
       --output predictions.json
   ```

3. **Validate results:**
   ```bash
   python /path/to/swe_bench_helper.py validate predictions.json
   ```

### For Development and Testing

1. **Dry run to see what would be executed:**
   ```bash
   python brokk_wrapper.py "Add logging" --dry_run
   ```

2. **Verbose output for debugging:**
   ```bash
   python brokk_wrapper.py "Fix error handling" --verbose
   ```

3. **Test on a specific model:**
   ```bash
   python brokk_wrapper.py "Optimize performance" --model "claude-3-sonnet"
   ```

## Error Handling

The wrapper handles several error scenarios:

- **Missing Brokk JAR**: Automatically builds using `gradlew shadowJar`
- **Invalid project paths**: Validates paths exist and target has git repo
- **Brokk CLI failures**: Continues to capture diff even if Brokk has issues
- **Git errors**: Proper error reporting for git operations
- **Format validation**: Ensures SWE-bench format compliance

## Integration with SWE-bench-Live

To integrate with the SWE-bench-Live evaluation harness:

1. **Create a prediction script:**
   ```python
   #!/usr/bin/env python3
   import subprocess
   import sys
   
   def generate_prediction(instance):
       cmd = [
           "python", "/path/to/brokk_wrapper.py",
           instance["problem_statement"],
           "--instance_id", instance["instance_id"],
           "--target_project", ".",
           "--output_file", f"pred_{instance['instance_id']}.json"
       ]
       subprocess.run(cmd, check=True)
       
       with open(f"pred_{instance['instance_id']}.json") as f:
           return json.load(f)
   ```

2. **Follow SWE-bench-Live submission format:**
   - Place predictions in `preds.json`
   - Include evaluation report in `results.json`
   - Add logs and trajectories as needed

## Troubleshooting

### Common Issues

1. **"No brokk*.jar found"**
   - Run `./gradlew shadowJar` to build the JAR
   - Check that you're in the Brokk project root

2. **"Target project is not a git repository"**
   - Ensure the target directory has a `.git` folder
   - Initialize git repo if needed: `git init`

3. **"Could not find Brokk project root"**
   - Use `--brokk_project` to specify the path
   - Ensure gradlew exists in the Brokk project

4. **Java memory issues**
   - The wrapper uses `-Xmx2G` by default
   - For larger projects, modify the memory settings in `execute_brokk_cli()`

5. **Model configuration**
   - Ensure your model is properly configured in Brokk
   - Use `--model` to override the default model

### Debug Mode

For detailed debugging, use:

```bash
python brokk_wrapper.py "your task" --verbose --dry_run
```

This shows exactly what would be executed without actually running Brokk.
