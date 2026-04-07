# Performance Baseline Testing

The TreeSitterRepoRunner provides comprehensive performance analysis for TreeSitter analyzers across major open source projects.

## Quick Start
```bash
# Get help and see available commands
scripts/run-treesitter-repos.sh help

# Set up test projects (downloads major projects)
scripts/run-treesitter-repos.sh setup

# Test specific project with specific language
scripts/run-treesitter-repos.sh openjdk-java --max-files 500

# Memory stress test to find limits
scripts/run-treesitter-repos.sh stress --project chromium --language cpp

# Compare Java frameworks
scripts/run-treesitter-repos.sh java-frameworks
```

## Direct Gradle Usage
```bash
# All commands work directly with Gradle too
./gradlew runTreeSitterRepoRunner -Pargs="help"
./gradlew runTreeSitterRepoRunner -Pargs="test-project --project vscode --language typescript --max-files 200"
```

## Commands Available

### Setup & Project Testing
- **`setup`** - Download/clone all test projects (Chromium, OpenJDK, VS Code, etc.)
- **`test-project`** - Test specific project: requires `--project <name> --language <lang>`
- **`directory`** - Analyze custom directory: requires `--directory <absolute-path> --language <lang>`
- **`multi-language`** - Multi-language analysis on Chromium (C++, JavaScript, Python)

### Predefined Tests
- **`openjdk-java`** - OpenJDK Java analysis
- **`chromium-cpp`** - Chromium C++ analysis
- **`vscode-ts`** - VS Code TypeScript analysis
- **`spring-java`** - Spring Framework Java analysis
- **`java-frameworks`** - Compare Kafka, Hibernate, Spring performance
- **`java-enterprise`** - Test Elasticsearch, IntelliJ Community

### Stress Testing
- **`stress`** - Memory stress test with increasing file counts until OutOfMemoryError
- **`memory-stress`** - Alias for stress testing
- **`quick`** - Quick baseline test with 100 files max

### Comprehensive Testing
- **`run-baselines`** - Full baseline suite across all major projects (takes hours)
- **`full`** - Alias for comprehensive baselines

### Cleanup & Maintenance
- **`cleanup`** - Clean up report files from output directory

## Options

### File Limits
- **`--max-files N`** - Maximum files to process (default: 1000)
- Higher limits test scalability but increase memory usage and runtime

### Output Control
- **`--output <path>`** - Output directory (default: baseline-results/)
- **`--json`** - Output results in JSON format
- **`--verbose`** - Enable detailed logging
- **`--show-details`** - Show symbols found in each file during analysis
- **`--cleanup`** - Clean up report files before running command

### Memory Profiling
- **`--memory-profile`** - Enable detailed memory monitoring
- **`--stress-test`** - Run until OutOfMemoryError to find limits

### Project Configuration
- **`--projects-dir <path>`** - Base directory for cloned projects (default: ../test-projects/)
- **`--directory <path>`** - Analyze files in specific directory instead of predefined projects (use absolute paths)
- **`--project <name>`** - Specific project name for test-project command
- **`--language <lang>`** - Language to analyze (cpp, java, typescript, javascript, python)

**Note**: Avoid placing test projects within the Brokk source directory to prevent conflicts with builds and version control. The default location (`../test-projects/`) keeps them separate.

## Test Projects

The baseline runner tests against real-world codebases:

### C++ Projects
- **Chromium** - Massive browser codebase (~50GB, millions of files)
- **LLVM** - Compiler infrastructure project

### Java Projects
- **OpenJDK** - Java runtime implementation
- **Spring Framework** - Enterprise application framework
- **Kafka** - Distributed streaming platform
- **Elasticsearch** - Search and analytics engine
- **Hibernate ORM** - Object-relational mapping framework
- **IntelliJ Community** - IDE implementation

### TypeScript/JavaScript Projects
- **VS Code** - Microsoft's code editor
- **Chromium JS** - JavaScript components of Chromium

### Python Projects
- **Chromium Python** - Build scripts and tools

## Output Formats

### JSON Output (`baseline-TIMESTAMP.json`)
Structured data with detailed metrics:
```json
{
  "timestamp": "2024-01-15T10:30:00",
  "results": {
    "openjdk": {
      "java": {
        "files_processed": 1000,
        "analysis_time_seconds": 45.2,
        "peak_memory_mb": 2048.5,
        "memory_per_file_kb": 2048.5,
        "failed": false
      }
    }
  }
}
```

### CSV Output (`baseline-TIMESTAMP.csv`)
Spreadsheet-compatible format for analysis and graphing.

### Text Summary (`baseline-TIMESTAMP-summary.txt`)
Human-readable summary with success/failure breakdown.

### Memory Stress Logs (`memory-stress-PROJECT-LANGUAGE-TIMESTAMP.txt`)
Detailed logs from memory stress testing showing performance at different file counts.

### Detailed Symbol Output (`--show-details`)
When using `--show-details`, each file shows discovered symbols:
```
📄 stores/symbolCacheStore.ts (3 symbols):
  - VARIABLE: symbolCache
  - FUNCTION: getSymbolsFromCache
  - FUNCTION: updateSymbolCache

📄 types.ts (5 symbols):
  - INTERFACE: SymbolInfo
  - TYPE_ALIAS: FileNode
  - ENUM: MessageType
  - FUNCTION: isValidSymbol
  - CLASS: TreeNode
```

## Performance Characteristics

### Memory Usage Patterns
- **Small projects (< 500 files)**: 100-500 MB typical
- **Medium projects (500-2000 files)**: 500MB-2GB typical
- **Large projects (2000+ files)**: 2GB+ (may hit limits)
- **Memory per file**: Usually 0.5-2 MB per file for TreeSitter parsing

### Scaling Behavior
- **Linear scaling**: Most analyzers scale linearly with file count
- **Cache benefits**: Repeated runs show improved performance from caching
- **Memory pressure**: Large projects may trigger GC pressure or OOM

### Typical Results
- **Java projects**: 50-200 files/second processing rate
- **C++ projects**: 20-100 files/second (more complex parsing)
- **TypeScript**: 100-300 files/second
- **Cache hit rates**: 60-90% on repeated runs

## JVM Configuration

The baseline runner uses optimized JVM settings:
- **Heap**: 8GB maximum (`-Xmx8g`)
- **Garbage Collector**: ZGC (`-XX:+UseZGC`) for low-latency
- **Experimental Features**: Enabled for latest performance optimizations
- **Assertions**: Enabled (`-ea`) for debugging

## Cleanup & Report Management

The baseline runner creates various report files that can accumulate over time. Use cleanup functionality to manage these files:

### Cleanup Command
```bash
# Clean up all report files from output directory
scripts/run-treesitter-repos.sh cleanup

# Clean up with verbose output showing each deleted file
scripts/run-treesitter-repos.sh --verbose cleanup

# Clean up from custom output directory
scripts/run-treesitter-repos.sh --output /path/to/reports cleanup
```

### Cleanup Before Running Tests
```bash
# Clean up old reports before running new baseline
scripts/run-treesitter-repos.sh --cleanup run-baselines

# Clean up before custom directory analysis
scripts/run-treesitter-repos.sh --cleanup --directory /absolute/path/to/project --language java
```

### Files Cleaned
The cleanup functionality removes:
- **`baseline-*.json`** - JSON report files with detailed metrics
- **`baseline-*.csv`** - CSV files for spreadsheet analysis
- **`baseline-*-summary.txt`** - Text summary reports
- **`memory-stress-*.txt`** - Memory stress test logs

Other files in the output directory are preserved - only report files generated by this tool are removed.

### Default Report Location
- **Default directory**: `build/reports/treesitter-baseline/` (aligned with Gradle's report structure)
- **Custom directory**: Use `--output <path>` to specify different location
- **Absolute paths**: Recommended for consistent results

## Example Workflows

### Find Memory Limits
```bash
# Test how many files can be processed before OOM
scripts/run-treesitter-repos.sh stress --project openjdk --language java
```

### Compare Language Performance
```bash
# Test same project with different languages
scripts/run-treesitter-repos.sh test-project --project chromium --language cpp --max-files 500
scripts/run-treesitter-repos.sh test-project --project chromium --language javascript --max-files 500
scripts/run-treesitter-repos.sh test-project --project chromium --language python --max-files 500
```

### Analyze Custom Project
```bash
# Test your own codebase (use absolute paths for --directory)
scripts/run-treesitter-repos.sh directory --directory /absolute/path/to/my/project --language java --max-files 1000

# Show detailed symbol information for each file
scripts/run-treesitter-repos.sh directory --directory /absolute/path/to/my/project --language typescript --show-details
```

### Performance Regression Testing
```bash
# Before code changes
scripts/run-treesitter-repos.sh openjdk-java --max-files 1000 --json

# After code changes
scripts/run-treesitter-repos.sh openjdk-java --max-files 1000 --json

# Compare results in baseline-results/
```

## Troubleshooting

### OutOfMemoryError
- Reduce `--max-files` parameter
- Increase JVM heap in `build.gradle.kts` if needed
- Check that other applications aren't consuming memory

### No Files Found
- Verify project exists in test-projects/ directory
- Check language spelling (cpp, java, typescript, javascript, python)
- Run setup command first: `scripts/run-treesitter-repos.sh setup`
- For `--directory` flag, ensure you're using absolute paths

### Analyzer Not Available
- Some analyzers may not be available on all branches
- Check console output for "Warning: XAnalyzer not available" messages
- Simplified version may have reduced analyzer support

### Path Resolution Issues
- Always use absolute paths with `--directory` flag
- Relative paths are resolved from the Java application's working directory, not the shell
- Use `realpath /path/to/directory` to convert relative to absolute paths