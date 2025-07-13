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

### Advanced Tasks
- `./gradlew shadowDistZip` - Create distribution ZIP with shadow JAR
- `./gradlew shadowDistTar` - Create distribution TAR with shadow JAR
- `./gradlew startShadowScripts` - Create OS-specific launch scripts

## Build Caching & Performance

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

## Icon Browser

To explore available Look and Feel icons for UI development:
- GUI browser: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil icons"`
- Console list: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil"`

Use `SwingUtil.uiIcon("IconName")` to safely load icons with automatic fallbacks.