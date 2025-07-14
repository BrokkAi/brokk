# Development Guide

## Essential Gradle Tasks for New Developers

### Quick Start
- `./gradlew run` - Run the application
- `./gradlew build` - Full build (compile, test, check)
- `./gradlew assemble` - Build without tests

### Development Workflow
- `./gradlew compileScala` - Compile main source code only
- `./gradlew clean` - Clean build artifacts
- `./gradlew shadowJar` - Create fat JAR for distribution (explicit only)
- `./gradlew classes` - Compile all main sources (fastest for development)

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

#### Test Reports
After running tests, detailed reports are automatically generated:
- **HTML Report**: `build/reports/tests/test/index.html` - Interactive test results with timing and failure details
- **Console Output**: Real-time test progress with pass/fail/skip status

### Code Formatting
- `./gradlew spotlessCheck` - Check Scala code formatting
- `./gradlew spotlessApply` - Auto-format all Scala code
- `./gradlew spotlessScalaCheck` - Check only Scala files
- `./gradlew spotlessScalaApply` - Format only Scala files

Configuration is in `.scalafmt.conf` - uses Scalafmt 3.8.1 with project-specific settings.

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
- **Development build** (`classes`): ~3-10 seconds (compile only)
- **JAR creation** (`shadowJar`): +10-30 seconds when explicitly requested

### Cache Management
- `./gradlew clean` - Clear build outputs (keeps Gradle caches)
- `./gradlew --stop` - Stop Gradle daemon
- Manual cache clearing: `rm -rf ~/.gradle/caches/` (nuclear option)

### Performance Tips
- Keep Gradle daemon running (`gradle.properties` enables this with 6GB heap)
- Use `./gradlew classes` for fastest development (compile only)
- Use `./gradlew assemble` for development (skips tests, no JARs)
- Configuration cache automatically optimizes repeated builds
- Compiler uses 2GB heap with G1GC for faster compilation
- File system watching enabled for better incremental builds

### JAR Creation
- **Development builds** (`build`, `assemble`) skip JAR creation for speed
- **Explicit JAR creation**: `./gradlew shadowJar` when needed
- **CI/Release builds**: `CI=true ./gradlew build` includes JAR automatically
- **Force JAR in build**: `./gradlew -PenableShadowJar build`

## Versioning

The project uses automatic versioning based on git tags. Version numbers are derived dynamically from the git repository state:

### Version Behavior
- **Clean Release**: `0.12.1` (when on exact git tag)
- **Development Build**: `0.12.1-30-g77dcc897` (30 commits after tag 0.12.1, commit hash 77dcc897)
- **Dirty Working Directory**: `0.12.1-30-g77dcc897-SNAPSHOT` (uncommitted changes present)
- **No Git Available**: `0.0.0-UNKNOWN` (fallback for CI environments without git)
- **BuildInfo class**: Contains `version` field with current version string

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
