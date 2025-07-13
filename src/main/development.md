# Development Guide

## Essential Gradle Tasks for New Developers

### Quick Start
- `./gradlew run` - Run the application
- `./gradlew build` - Full build (compile, test, check)
- `./gradlew assemble` - Build without tests

### Development Workflow
- `./gradlew compileScala` - Compile main source code only
- `./gradlew clean` - Clean build artifacts
- `./gradlew shadowJar` - Create fat JAR for distribution

### Testing
- `./gradlew test` - Run all tests (includes TreeSitter and regular tests)
- `./gradlew check` - Run all checks (tests + static analysis)

#### Running Test Subsets
- `./gradlew test --tests "*AnalyzerTest"` - Run all analyzer tests (includes TreeSitter)
- `./gradlew test --tests "*.EditBlockTest"` - Run specific test class
- `./gradlew test --tests "*EditBlock*"` - Run tests matching pattern
- `./gradlew test --tests "io.github.jbellis.brokk.git.*"` - Run tests in package
- `./gradlew test --tests "*TypescriptAnalyzerTest"` - Run TreeSitter analyzer tests

#### Test Configuration Notes
- **Unified test suite**: All tests run together in a single forked JVM
- **Single fork strategy**: One JVM fork for the entire test run
- **Native library isolation**: TreeSitter tests safely isolated while maintaining good performance
- **Optimal performance**: Single fork overhead instead of per-test or per-suite forking

### Code Formatting
Note: Scalafmt plugin is not currently configured in this build. For now, follow the existing code style manually.

The build system uses aggressive multi-level caching for optimal performance:

### Cache Types
- **Local Build Cache**: Task outputs cached in `~/.gradle/caches/`
- **Configuration Cache**: Build configuration cached for faster startup
- **Incremental Compilation**: Only recompiles changed files
- **Daemon Caching**: JVM process reused across builds

### Expected Performance
- **First build**: ~30-60 seconds (everything compiled)
- **No-change build**: ~1-3 seconds (all tasks `UP-TO-DATE`)
- **Incremental build**: ~5-15 seconds (only affected tasks run)

### Cache Management
- `./gradlew clean` - Clear build outputs (keeps Gradle caches)
- `./gradlew --stop` - Stop Gradle daemon
- Manual cache clearing: `rm -rf ~/.gradle/caches/` (nuclear option)

### Performance Tips
- Keep Gradle daemon running (`gradle.properties` enables this)
- Use `./gradlew assemble` for development (skips tests)
- Configuration cache automatically optimizes repeated builds

## Versioning

The project uses automatic versioning based on git tags. Version numbers are derived dynamically from the git repository state:

### Version Behavior
- **Clean Release**: `0.12.1` (when on exact git tag)
- **Development Build**: `0.12.1-30-g77dcc897` (30 commits after tag 0.12.1, commit hash 77dcc897)
- **Dirty Working Directory**: `0.12.1-30-g77dcc897-SNAPSHOT` (uncommitted changes present)
- **No Git Available**: `0.0.0-UNKNOWN` (fallback for CI environments without git)

### How It Works
The build automatically calls `git describe --tags` to determine the version:
1. **On exact tag**: Returns clean version number (e.g., `0.12.1`)
2. **Between tags**: Returns tag + commit count + hash (e.g., `0.12.1-30-g77dcc897`)
3. **With uncommitted changes**: Adds `-SNAPSHOT` suffix
4. **Error fallback**: Uses `0.0.0-UNKNOWN` if git is unavailable

### Version Usage
- **JAR files**: Get correct version in filename (e.g., `brokk-0.12.1-30-g77dcc897-SNAPSHOT.jar`)
- **BuildInfo class**: Contains `version` field with current version string
- **Runtime access**: Use `BuildInfo.version` in code to get current version

### Creating Releases
To create a new release:
1. Tag the commit: `git tag v0.13.0`
2. Build will automatically use clean version: `0.13.0`
3. Push tag: `git push origin v0.13.0`

No manual version updates needed - everything is derived from git tags automatically.

## Icon Browser

To explore available Look and Feel icons for UI development:
- GUI browser: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil icons"`
- Console list: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil"`

Use `SwingUtil.uiIcon("IconName")` to safely load icons with automatic fallbacks.
