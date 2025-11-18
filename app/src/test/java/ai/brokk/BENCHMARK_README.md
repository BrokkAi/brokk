# File Watcher Benchmark Suite

Comprehensive benchmarking suite for comparing Legacy and Native file watcher implementations.

## Overview

This benchmark suite measures:
- **Latency**: Time from file modification to event detection
- **System Overhead**: CPU and memory usage during watching
- **Bulk Changes**: Behavior with many simultaneous file changes
- **Idle Overhead**: Resource consumption when no changes occur

## Components

### Core Classes

1. **`FileWatcherBenchmark`**: Main benchmark implementation
   - Latency measurement
   - Resource metrics collection
   - Bulk change testing
   - Idle overhead monitoring

2. **`TestProjectGenerator`**: Creates test projects of various sizes
   - Small: 100 files, depth 3-5
   - Medium: 10,000 files, depth 5-10
   - Large: 100,000 files, depth 8-15

3. **`FileWatcherBenchmarkTest`**: JUnit test suite
   - Runs comparative benchmarks
   - Validates performance expectations
   - Tagged with `@Tag("benchmark")` for selective execution

4. **`FileWatcherBenchmarkRunner`**: Standalone CLI runner
   - Generates detailed reports
   - Saves results to files
   - Can run specific benchmarks or full suite

## Running Benchmarks

### Option 1: JUnit Tests (Recommended for Development)

Run specific benchmark tests:
```bash
./gradlew test --tests FileWatcherBenchmarkTest.testLatencySmallProject
```

Run all benchmark tests:
```bash
./gradlew test --tests FileWatcherBenchmarkTest
```

Run only benchmark-tagged tests:
```bash
./gradlew test -Dtest.single=FileWatcherBenchmarkTest -Dtags=benchmark
```

### Option 2: Standalone Runner (Recommended for Comprehensive Testing)

Run all benchmarks and save report:
```bash
./gradlew test --tests FileWatcherBenchmarkRunner
```

Run specific benchmark:
```bash
java -cp <classpath> ai.brokk.FileWatcherBenchmarkRunner latency-small
```

Available benchmarks:
- `latency-small`: Latency on small project (100 files)
- `latency-medium`: Latency on medium project (10K files)
- `latency-large`: Latency on large project (100K files)
- `bulk-small`: Bulk changes (10 files)
- `bulk-medium`: Bulk changes (100 files)
- `bulk-large`: Bulk changes (500 files)
- `idle-small`: Idle overhead on small project
- `idle-medium`: Idle overhead on medium project
- `idle-large`: Idle overhead on large project

Specify output directory:
```bash
java -cp <classpath> ai.brokk.FileWatcherBenchmarkRunner --output=./my-results
```

## Interpreting Results

### Latency Metrics

- **P50 (Median)**: Half of changes detected faster than this
- **P95**: 95% of changes detected faster than this
- **P99**: 99% of changes detected faster than this
- **Avg**: Average detection time

**Good targets:**
- P50 < 100ms (very responsive)
- P95 < 500ms (acceptable for most scenarios)
- P99 < 1000ms (handles edge cases reasonably)

### Resource Metrics

- **CPU Time**: Total CPU time consumed by watcher threads
  - Lower is better
  - Compare relative overhead between implementations

- **Peak Memory**: Maximum memory used during test
  - Monitor for memory leaks
  - Compare memory efficiency

- **Wall Time**: Real-time duration
  - Includes debouncing delays
  - Not always representative of watcher efficiency

### Comparison Output

Example:
```
COMPARISON SUMMARY
================================================================================
Latency Improvements (Native vs Legacy):
  P50: +45.2% (Native faster/less)
  P95: +38.7% (Native faster/less)
  P99: +12.3% (Native faster/less)

Resource Usage (Native vs Legacy):
  CPU Time: +65.1% (Native faster/less)
  Peak Memory: -5.2% (Legacy faster/less)
================================================================================
```

Interpretation:
- **Positive %**: Native is better (faster/uses less)
- **Negative %**: Legacy is better
- **+45.2% on P50**: Native detects changes 45% faster
- **+65.1% on CPU**: Native uses 65% less CPU
- **-5.2% on Memory**: Native uses 5% more memory

## Benchmark Architecture

### Metrics Collection

```
MetricsCollector
├── CPU Time Sampling (every 50ms)
│   └── Aggregates thread CPU usage
├── Memory Sampling (every 50ms)
│   └── Tracks heap usage
└── Latency Measurement
    └── Nanotime precision for file events
```

### Test Flow

```
1. Generate test project
2. Start watch service (Legacy or Native)
3. Begin metrics collection
4. Perform file operations
5. Measure event detection latency
6. Stop metrics collection
7. Clean up
8. Report results
```

### Implementation Selection

Benchmarks use:
```java
private IWatchService createWatchService(
    Path projectRoot,
    String implementation,
    List<IWatchService.Listener> listeners
) {
    if ("native".equalsIgnoreCase(implementation)) {
        return new NativeProjectWatchService(projectRoot, null, null, listeners);
    } else if ("legacy".equalsIgnoreCase(implementation)) {
        return new LegacyProjectWatchService(projectRoot, null, null, listeners);
    }
}
```

## OS-Level Monitoring (Advanced)

For deeper I/O analysis, use OS tools alongside benchmarks:

### macOS

Monitor file system events:
```bash
sudo fs_usage -f filesys | grep -i brokk
```

Monitor I/O operations:
```bash
sudo iotop -P java
```

Activity Monitor:
- Open Activity Monitor
- Filter for "java" processes
- Check "Disk" and "CPU" tabs during benchmark

### Linux

Monitor I/O:
```bash
sudo iotop -o -P
```

Monitor syscalls:
```bash
strace -e trace=file,desc -p <java-pid> 2>&1 | grep -E 'stat|open|read'
```

Monitor inotify usage:
```bash
sudo sysctl fs.inotify
```

### Profiling with JProfiler/YourKit

1. Attach profiler to benchmark JVM
2. Focus on:
   - Thread activity (watcher threads)
   - CPU hotspots (polling loops)
   - Allocation hotspots (event batching)
3. Compare profiles between implementations

## Customizing Benchmarks

### Adding New Benchmark

1. Add method to `FileWatcherBenchmark.java`:
```java
public BenchmarkResult runMyCustomBenchmark(
    Path projectRoot,
    int param,
    String implementation
) throws Exception {
    // Your benchmark logic
}
```

2. Add test to `FileWatcherBenchmarkTest.java`:
```java
@Test
@Tag("benchmark")
void testMyCustomBenchmark() throws Exception {
    // Setup and run
}
```

3. Add to `FileWatcherBenchmarkRunner.java`:
```java
case "my-custom" -> runMyCustom();
```

### Adjusting Project Sizes

Edit `TestProjectGenerator.java`:
```java
public enum ProjectSize {
    TINY(10, 2, 3),           // New size
    SMALL(100, 3, 5),
    MEDIUM(10_000, 5, 10),
    LARGE(100_000, 8, 15),
    HUGE(500_000, 10, 20);    // New size
    // ...
}
```

## Troubleshooting

### "Too many open files" error
Increase file descriptor limit:
```bash
ulimit -n 10240
```

### Tests timeout
Increase timeout in test annotations or adjust iteration counts.

### Inconsistent results
- Run multiple times and average
- Close other applications
- Disable aggressive power saving
- Use dedicated test machine

### Native implementation fails to start
Check logs for initialization errors. May need platform-specific dependencies.

## CI/CD Integration

### GitHub Actions Example

```yaml
- name: Run File Watcher Benchmarks
  run: ./gradlew test --tests FileWatcherBenchmarkRunner

- name: Upload Benchmark Results
  uses: actions/upload-artifact@v3
  with:
    name: benchmark-results
    path: benchmark-results/*.txt
```

### Performance Regression Detection

Compare benchmark reports over time:
1. Store baseline results
2. Run benchmarks on each PR
3. Alert if performance degrades >10%
4. Use tools like `benchmarkjs` or custom scripts

## Best Practices

1. **Warm-up**: Run one iteration before measuring to warm up JIT
2. **Isolation**: Close unnecessary applications
3. **Repetition**: Run multiple times, report median
4. **Project Realism**: Use project structures similar to real codebases
5. **Environment Documentation**: Record OS, hardware, Java version
6. **Baseline**: Establish baseline before making changes

## Example Report

```
File Watcher Benchmark Report
Generated: 2025-01-17T14:30:22
================================================================================

Benchmark: latency-small
================================================================================

LEGACY IMPLEMENTATION:
  Iterations: 50
  P50 Latency: 125.32 ms
  P95 Latency: 287.45 ms
  P99 Latency: 456.78 ms
  CPU Time: 1245.67 ms
  Peak Memory: 45.23 MB

NATIVE IMPLEMENTATION:
  Iterations: 50
  P50 Latency: 68.15 ms
  P95 Latency: 176.34 ms
  P99 Latency: 398.12 ms
  CPU Time: 435.89 ms
  Peak Memory: 47.56 MB

COMPARISON:
  P50 Latency: +45.6% (Native better)
  P95 Latency: +38.7% (Native better)
  P99 Latency: +12.8% (Native better)
  CPU Time: +65.0% (Native better)
  Memory: -5.2% (Legacy better)

================================================================================
```

## Further Reading

- Issue #1191: Improve File Watcher
- Issue #1584: Too Many Open Files
- `plans/issue-1191-improve-file-watcher.md`
- `plans/issue-1584-too-many-open-files.md`
