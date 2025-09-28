# Brokk CLI Documentation

The Brokk CLI is a unified command-line interface that allows you to run Brokk's AI agents in a non-interactive, "one-shot" mode. It's designed for scripting, automation, and integration with evaluation frameworks like SWE-bench-lite.

## Table of Contents

- [Quick Start](#quick-start)
- [Installation & Setup](#installation--setup)
- [Basic Usage](#basic-usage)
- [Available Commands](#available-commands)
- [Advanced Usage](#advanced-usage)
- [SWE-bench-lite Integration](#swe-bench-lite-integration)
- [Troubleshooting](#troubleshooting)
- [Integration Examples](#integration-examples)
- [Best Practices](#best-practices)

## Quick Start

```bash
# Ask a question about your codebase
./cli --ask "What is this project about?"

# Search for specific patterns
./cli --search "How does authentication work?"

# Generate or modify code
./cli --code "Add error handling to the login method"

# SWE-bench-lite evaluation
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 5
```

## Installation & Setup

### Prerequisites

- Java 21 or later
- Git repository (Brokk CLI requires a Git repo)
- Gradle (for building)

### Building the CLI

1. **Clone and build the project:**
   ```bash
   git clone <brokk-repo-url>
   cd brokk
   ./gradlew shadowJar
   ```

2. **Verify the build:**
   ```bash
   ./cli --help
   ```

The `shadowJar` task creates a self-contained JAR file with all dependencies bundled, which is required for CLI operation.

### Project Requirements

- **Git Repository**: The CLI requires a Git repository to function. It will automatically detect if you're in a Git repo.
- **Project Structure**: Works best with standard project structures (Maven, Gradle, etc.)

## Basic Usage

### Direct CLI Usage

Brokk CLI provides a direct command-line interface:

```bash
# Basic syntax (uses current directory as project)
./cli --ask "What is this project about?"
./cli --code "Add error handling to the login method"
./cli --search "Find all database connections"

# With specific project
./cli --project /path/to/project --ask "What is the main class?"
./cli --project /path/to/project --code "Fix the authentication bug"

# Examples
./cli --ask "What is the main class?"
./cli --code "Add logging to the main method"
./cli --search "Find all database connections"
./cli --project /path/to/other/project --ask "What is this project about?"
```

## Available Commands

### Core Actions

| Command | Description | Example |
|---------|-------------|---------|
| `--ask` | Ask questions about the codebase | `./cli --ask "What is the main class?"` |
| `--code` | Generate or modify code | `./cli --code "Add error handling"` |
| `--search` | Search for patterns and build workspace | `./cli --search "Find all API endpoints"` |
| `--architect` | Complex multi-step architectural tasks | `./cli --architect "Refactor the authentication system"` |
| `--merge` | Resolve Git merge conflicts | `./cli --merge` |

### Workspace Building Options

| Option | Description | Example |
|--------|-------------|---------|
| `--edit` | Add files for editing | `--edit src/main/java/Main.java` |
| `--add-class` | Add class summaries | `--add-class com.example.User` |
| `--add-usage` | Add symbol usages | `--add-usage com.example.User.getName` |
| `--add-callers` | Add method callers | `--add-callers com.example.User.getName=2` |
| `--add-callees` | Add method callees | `--add-callees com.example.User.getName=2` |
| `--add-url` | Add content from URL | `--add-url https://example.com/docs` |

### Model Overrides

| Option | Description | Example |
|--------|-------------|---------|
| `--model` | Override task model | `--model gpt-4` |
| `--codemodel` | Override code model | `--codemodel claude-3` |

### Advanced Options

| Option | Description | Example |
|--------|-------------|---------|
| `--deepscan` | Auto-discover relevant context | `--deepscan` |
| `--worktree` | Create isolated Git worktree | `--worktree /tmp/brokk-work` |

## Direct CLI Interface

The CLI provides direct access to all Brokk functionality:

```bash
# Usage
./cli --ask "your query here"
./cli --code "your query here"
./cli --search "your query here"
./cli --project /path/to/project --ask "your query here"

# Examples
./cli --ask "What is this project about?"
./cli --search "How does authentication work?"
./cli --code "Add error handling to the login method"
./cli --project /path/to/other/project --ask "What is the main class?"
```

**Features:**
- Direct access to all Brokk commands
- Automatic file discovery from prompts
- Headless mode for automation
- Full control over all options


## SWE-bench-lite Integration

### Complete Evaluation Workflow

Brokk CLI integrates seamlessly with SWE-bench-lite for automated code generation evaluation. The workflow consists of two main steps:

#### 1. Repository Setup

First, set up the SWE-bench-lite repositories:

```bash
# Set up repositories for evaluation
python3 SWE-bench_lite/repo_setup.py --split test --max_repos 5 --repos_dir swe_bench_repos
```

This script:
- Loads the SWE-bench-lite dataset
- Clones GitHub repositories for each instance
- Checks out the specific commits
- Creates a mapping file for evaluation

#### 2. Run Evaluation

Then, evaluate Brokk CLI on the repositories:

```bash
# Evaluate on test instances
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 5 --repos_dir swe_bench_repos

# Evaluate on dev instances
python3 SWE-bench_lite/evaluate_brokk.py --split dev --repos_dir swe_bench_repos

# Custom model name and output file
python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 3 --model_name "brokk-cli-v1.0" --output_file "my_predictions.json"
```

### Evaluation Features

- **Automated Repository Management**: Handles cloning and commit checkout
- **Real-time Progress Tracking**: Shows detailed status updates during evaluation
- **Git Integration**: Captures actual code changes as patches
- **Error Handling**: Robust error handling with detailed logging
- **Preds.json Generation**: Outputs results in the required SWE-bench format
- **Colored Output**: Clear success/error reporting with timestamps

### Example Evaluation Output

```bash
$ python3 SWE-bench_lite/evaluate_brokk.py --split test --max_instances 2
[21:52:41] ℹ️  Loading SWE-bench-lite dataset from SWE-bench_lite
[21:52:41] ✅ Loaded dataset with 23 dev instances and 300 test instances
[21:52:42] ✅ Loaded repository mapping with 5 entries
[21:52:42] ℹ️  Found 5 instances with available repositories
[21:52:42] ℹ️  Starting evaluation of 2 instances
[21:52:42] ℹ️  Model name: brokk-cli

============================================================
Instance 1/2: django__django-15213
============================================================
[21:52:42] ℹ️  Running Brokk CLI on django__django-15213
[21:52:42] ℹ️  Repository: swe_bench_repos/django__django_django__django-15213
[21:52:42] ℹ️  Problem: ExpressionWrapper for ~Q(pk__in=[]) crashes...
[21:52:42] ℹ️  Checking initial git status...
[21:52:42] ℹ️  Initial commit: 03cadb912c78b769d6bf4a943a2a35fc1d952960
[21:52:42] ℹ️  Starting Brokk CLI execution...
[21:52:42] 🔄 Brokk CLI is analyzing the problem and generating code...
[21:54:34] ℹ️  Brokk CLI execution completed in 112.2 seconds
[21:54:34] ✅ Brokk CLI completed successfully
[21:54:34] ℹ️  Checking for changes...
[21:54:37] ✅ Changes detected: M  django/db/models/query.py
[21:54:37] ℹ️  Generating git diff...
[21:54:37] ✅ Generated patch (1,234 characters)
[21:54:37] ✅ Instance django__django-15213: Changes made
[21:54:37] Progress: 1/2 (50.0%) - Success: 1, Failed: 0

============================================================
EVALUATION COMPLETE
============================================================
[21:54:37] ✅ Total instances: 2
[21:54:37] ✅ Successful evaluations: 2
[21:54:37] ✅ Success rate: 100.0%
[21:54:37] ✅ Predictions saved to preds.json
```

### Output Format

The evaluation generates a `preds.json` file in the SWE-bench format:

```json
{
  "django__django-15213": {
    "model_name_or_path": "brokk-cli",
    "instance_id": "django__django-15213",
    "model_patch": "diff --git a/django/db/models/query.py b/django/db/models/query.py\nindex 1234567..abcdefg 100644\n--- a/django/db/models/query.py\n+++ b/django/db/models/query.py\n@@ -10,6 +10,7 @@ class Query:\n     def __init__(self, model):\n+        # Fixed ExpressionWrapper for ~Q(pk__in=[]) issue\n         self.model = model\n         self.where = WhereNode()\n"
  }
}
```

## Advanced Usage

### File Discovery

The CLI automatically discovers files mentioned in your prompts:

```bash
# This automatically finds and adds BrokkCli.java to the workspace
./cli --code "Add logging to BrokkCli.java"

# Output:
# Auto-discovered file: app/src/main/java/io/github/jbellis/brokk/cli/BrokkCli.java
# Added 1 auto-discovered files to workspace
```

**Supported file patterns:**
- `.java`, `.kt` (Java/Kotlin)
- `.py` (Python)
- `.js`, `.ts` (JavaScript/TypeScript)
- `.go` (Go)
- `.rs` (Rust)
- `.cpp`, `.c`, `.h` (C/C++)
- `.cs` (C#)
- `.php`, `.rb`, `.swift`, `.scala`
- `.sql`, `.xml`, `.json`, `.yaml`
- `.md`, `.txt`, `.sh`, `.bat`

### Deep Scan

Use `--deepscan` to automatically discover relevant context:

```bash
./cli --project . --deepscan --code "Refactor the authentication system"
```

This runs a `ContextAgent` that analyzes your prompt and automatically adds relevant files to the workspace.

### Git Worktrees

Create isolated environments for testing changes:

```bash
./cli --project . --worktree /tmp/brokk-test --code "Add new feature"
```

This creates a detached worktree from the default branch's HEAD, allowing you to make changes without affecting your working directory.

### Model Selection

Override the default AI models:

```bash
# Use specific models for different tasks
./cli --project . --model gpt-4 --ask "Complex architectural question"
./cli --project . --codemodel claude-3 --code "Generate efficient algorithm"
```

## Troubleshooting

### Common Issues

#### 1. "NoClassDefFoundError: org/eclipse/jgit/api/errors/GitAPIException"

**Problem**: Missing dependencies in the JAR file.

**Solution**: 
```bash
# Rebuild the shadow JAR
./gradlew shadowJar
```

**Root Cause**: The CLI uses a shadow JAR that bundles all dependencies. Regular JAR files don't include runtime dependencies.

#### 2. "Unable to initialize main class io.github.jbellis.brokk.cli.BrokkCli"

**Problem**: Wrong execution method or missing dependencies.

**Solutions**:
- Use the shadow JAR: `java -jar app/build/libs/brokk-*.jar`
- Or use the convenience script: `./brokk ask "question"`

#### 3. "I need to see the contents of [file] to make the requested change"

**Problem**: File not found in workspace.

**Solutions**:
- **Automatic**: The CLI now auto-discovers files mentioned in prompts
- **Manual**: Use `--edit filename` to explicitly add files
- **Deep scan**: Use `--deepscan` to auto-discover relevant context

#### 4. "Project path is not a directory"

**Problem**: Invalid project path.

**Solution**: Ensure the path exists and is a directory:
```bash
./brokk ask --project /valid/path/to/project "question"
```

#### 5. "Brokk CLI requires to have a Git repo"

**Problem**: Not in a Git repository.

**Solution**: Initialize Git or navigate to a Git repository:
```bash
git init
# or
cd /path/to/git/repo
```

### Debugging

#### Enable Verbose Output

```bash
# Add debug flags
./cli --project . --ask "question" -Xmx2G -Dbrokk.debug=true
```

#### Check Workspace Contents

```bash
# See what files are in the workspace
./brokk ask "What files are currently in the workspace?"
```

#### Verify File Discovery

```bash
# Test file discovery
./brokk code "Add a comment to README.md"
# Should show: Auto-discovered file: README.md
```

### Performance Issues

#### Memory Issues

```bash
# Increase heap size
./cli --project . --ask "question" -Xmx4G
```

#### Slow File Discovery

For large projects, file discovery might be slow. Consider:
- Using `--edit` to explicitly specify files
- Using `--deepscan` for intelligent context discovery
- Excluding large directories in `.gitignore`

## Integration Examples

### SWE-bench-live Integration

```bash
#!/bin/bash
# Example script for SWE-bench-live integration

PROJECT_PATH="/path/to/test/project"
ISSUE_DESCRIPTION="Fix the authentication bug in the login method"

# Run Brokk CLI to analyze and fix the issue
./brokk code --project "$PROJECT_PATH" "$ISSUE_DESCRIPTION"

# Check if changes were made
if git diff --quiet; then
    echo "No changes made"
    exit 1
else
    echo "Changes applied successfully"
    git diff --stat
fi
```

### CI/CD Pipeline

```bash
#!/bin/bash
# Example CI/CD integration

# Analyze code quality
./brokk ask --project . "Are there any potential security vulnerabilities in this code?"

# Generate tests
./brokk code --project . "Add comprehensive unit tests for the UserService class"

# Code review
./brokk search --project . "Find all TODO comments and technical debt"
```

### Batch Processing

```bash
#!/bin/bash
# Process multiple issues

ISSUES=(
    "Add input validation to the API endpoints"
    "Improve error handling in the database layer"
    "Add logging to critical functions"
)

for issue in "${ISSUES[@]}"; do
    echo "Processing: $issue"
    ./brokk code --project . "$issue"
    echo "Completed: $issue"
    echo "---"
done
```

## Best Practices

### 1. Use Direct CLI Interface

Use the CLI directly for all operations:

```bash
# Good
./cli --ask "question"

# Also good with explicit project
./cli --project . --ask "question"
```

### 2. Leverage Auto-Discovery

Let the CLI automatically discover files:

```bash
# Good - mentions specific files
./cli --code "Add error handling to UserService.java"

# Less efficient - requires manual file addition
./cli --project . --edit src/main/java/UserService.java --code "Add error handling"
```

### 3. Use Deep Scan for Complex Tasks

For architectural changes or complex refactoring:

```bash
./cli --project . --deepscan --architect "Refactor the authentication system"
```

### 4. Create Isolated Environments

Use worktrees for experimental changes:

```bash
./cli --project . --worktree /tmp/experiment --code "Try new approach"
```

### 5. Specify Models for Different Tasks

Use appropriate models for different types of tasks:

```bash
# Use GPT-4 for complex reasoning
./cli --project . --model gpt-4 --architect "Design new architecture"

# Use Claude for code generation
./cli --project . --codemodel claude-3 --code "Generate efficient algorithm"
```

### 6. Combine with Git Workflow

```bash
# Create feature branch
git checkout -b feature/new-feature

# Use Brokk to implement
./cli --code "Implement the new feature"

# Review changes
git diff

# Commit if satisfied
git add .
git commit -m "Add new feature via Brokk CLI"
```

### 7. Error Handling in Scripts

```bash
#!/bin/bash
set -e  # Exit on error

# Run Brokk CLI
if ./cli --code "Add feature"; then
    echo "Success: Feature added"
else
    echo "Error: Failed to add feature"
    exit 1
fi
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `BRK_CODEAGENT_METRICS` | Enable CodeAgent metrics | `false` |
| `JAVA_HOME` | Java installation path | System default |
| `BROKK_DEBUG` | Enable debug logging | `false` |

## File Structure

```
brokk/
├── cli                     # Main CLI script
├── SWE-bench_lite/
│   ├── repo_setup.py      # Repository setup script
│   ├── evaluate_brokk.py  # Evaluation script
│   └── preds.json         # Generated predictions
├── app/build/libs/
│   ├── brokk-*.jar        # Shadow JAR (with dependencies)
│   └── app-*.jar          # Regular JAR (without dependencies)
└── CLI.md                 # This documentation
```
---
