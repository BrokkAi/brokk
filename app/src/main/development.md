# Development Guide

## Project Structure

This is a multi-module Gradle project with Kotlin DSL:

- **`analyzer-api`** - Core analyzer interfaces and types (1% of codebase, compiled with javac)
- **`app`** - Java 21 main application with TreeSitter analyzers (94% of codebase, compiled with javac)
- **`joern-analyzers`** - Scala 3.5.2 analyzers using Joern CPG (5% of codebase, compiled with scalac)

### Module Dependency Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        Module Dependencies                      │
└─────────────────────────────────────────────────────────────────┘

                    ┌──────────────────────┐
                    │        app           │
                    │                      │
                    │ • GUI Application    │
                    │ Compiler: javac      │
                    │ Size: ~94%           │
                    └──────────────────────┘
                             │
                             │
          ┌──────────────────┼──────────────────┐
          │ implementation                      │ implementation
          │                                     │
          ▼                                     ▼
┌─────────────────┐                 ┌─────────────────┐
│   analyzer-api  │◄────────────────│ joern-analyzers │
│                 │  implementation │                 │
│ • IAnalyzer     │                 │ • JavaAnalyzer  │
│ • CodeUnit      │                 │ • CppAnalyzer   │
│ • ProjectFile   │                 │ • JoernAnalyzer │
│ • BrokkFile     │                 │ • CPG Builder   │
│                 │                 │                 │
│ Compiler: javac │                 │ Compiler: scalac│
│ Size: ~1%       │                 │ Size: ~5%       │
└─────────────────┘                 └─────────────────┘

Dependencies:
• app → analyzer-api, joern-analyzers
• joern-analyzers → analyzer-api
• analyzer-api → (no dependencies)
```

## Essential Gradle Tasks for New Developers

### Quick Start
- `./gradlew run` - Run the application (from app)
- `./gradlew build` - Full build (compile, test, check) - all modules
- `./gradlew assemble` - Build without tests - all modules

### Development Workflow - All Projects
- `./gradlew clean` - Clean build artifacts for all modules
- `./gradlew classes` - Compile all main sources (fastest for development)
- `./gradlew shadowJar` - Create fat JAR for distribution (explicit only)

### Development Workflow - Individual Projects
- `./gradlew :analyzer-api:compileJava` - Compile API interfaces only
- `./gradlew :app:compileJava` - Compile Java code only
- `./gradlew :joern-analyzers:compileScala` - Compile Scala code only
- `./gradlew :analyzer-api:classes` - Compile analyzer-api (Java)
- `./gradlew :app:classes` - Compile app (Java)
- `./gradlew :joern-analyzers:classes` - Compile joern-analyzers (Scala)
- `./gradlew :analyzer-api:assemble` - Build API module only
- `./gradlew :app:assemble` - Build app project only
- `./gradlew :joern-analyzers:assemble` - Build Joern analyzers only

### Testing
- `./gradlew test` - Run all tests (includes TreeSitter and regular tests)
- `./gradlew check` - Run all checks (tests + static analysis)

#### Running Tests by Project
- `./gradlew :analyzer-api:test` - Run tests only in the API module
- `./gradlew :app:test` - Run tests only in the app Java project
- `./gradlew :joern-analyzers:test` - Run tests only in the Joern analyzers project

#### Running Test Subsets
- `./gradlew test --tests "*AnalyzerTest"` - Run all analyzer tests (includes TreeSitter)
- `./gradlew test --tests "*.EditBlockTest"` - Run specific test class
- `./gradlew test --tests "*EditBlock*"` - Run tests matching pattern
- `./gradlew test --tests "io.github.jbellis.brokk.git.*"` - Run tests in package
- `./gradlew test --tests "*TypescriptAnalyzerTest"` - Run TreeSitter analyzer tests

#### Project-Specific Test Patterns
- `./gradlew :analyzer-api:test --tests "*Analyzer*"` - Run API interface tests
- `./gradlew :app:test --tests "*GitRepo*"` - Run Git-related tests in app project
- `./gradlew :joern-analyzers:test --tests "*Analyzer*"` - Run analyzer tests in Joern project

#### Test Configuration Notes
- **Unified test suite**: All tests run together in a single forked JVM
- **Single fork strategy**: One JVM fork for the entire test run
- **Native library isolation**: TreeSitter tests safely isolated while maintaining good performance

#### Test Reports
After running tests, detailed reports are automatically generated:
- **All Projects**: `build/reports/tests/test/index.html` - Combined test results
- **analyzer-api**: `analyzer-api/build/reports/tests/test/index.html` - API module tests
- **app**: `app/build/reports/tests/test/index.html` - Java project tests
- **joern-analyzers**: `joern-analyzers/build/reports/tests/test/index.html` - Joern project tests
- **Console Output**: Real-time test progress with pass/fail/skip status

### Code Formatting

#### Scala Code (joern-analyzers)
- `./gradlew :joern-analyzers:scalafmt` - Format Scala code
- `./gradlew scalafmt` - Format all Scala code in project

### Dependency Management

The project uses the Gradle Dependency Analysis plugin to maintain clean dependencies:

- `./gradlew buildHealth` - Run comprehensive dependency analysis
- `./gradlew dependencies` - Show dependency tree with conflicts

#### What the dependency analysis checks:
- **Unused Dependencies**: Identifies dependencies that aren't actually used
- **Dependency Conflicts**: Detects version conflicts between transitive dependencies
- **Incorrect Configuration**: Finds dependencies in wrong configurations (e.g., runtime vs compile)
The dependency analysis runs automatically in CI and will fail builds on critical dependency issues.

### Static Analysis

#### ErrorProne (Java modules: analyzer-api, app)
- **Configuration**: Custom checks enabled in `build.gradle.kts` for enhanced error detection
- **Scope**: Applied to analyzer-api and app modules (Java code only)

#### NullAway (app module)
- **Null safety analysis**: Static analysis to prevent NullPointerExceptions
- **Configuration**: Configured with project-specific null safety rules
- **Scope**: Applied to app module only where most business logic resides

The build system uses aggressive multi-level caching for optimal performance:

### Cache Types
- **Local Build Cache**: Task outputs cached in `~/.gradle/caches/`
- **Configuration Cache**: Build configuration cached for faster startup
- **Incremental Compilation**: Only recompiles changed files
- **Daemon Caching**: JVM process reused across builds

### Cache Management
- `./gradlew clean` - Clear build outputs (keeps Gradle caches)
- `./gradlew --stop` - Stop Gradle daemon
- Manual cache clearing: `rm -rf ~/.gradle/caches/` (nuclear option)

### Performance Tips
- Keep Gradle daemon running (`gradle.properties` enables this with 6GB heap)
- Use `./gradlew :app:classes` for fastest Java-only development
- Use `./gradlew :joern-analyzers:classes` for Scala-only development
- Use `./gradlew classes` to compile both projects
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
- `./gradlew printVersion` - Print the current version number

### Creating Releases
To create a new release:
1. **Tag the commit**: `git tag v0.13.0`
2. **Build will automatically use clean version**: `0.13.0`
3. **Push tag to trigger release**: `git push origin v0.13.0`
4. **Wait for GitHub Actions** to complete (creates release + uploads JAR)
5. **Update JBang catalog locally**:
   ```bash
   # Uses latest git tag automatically
   ./scripts/update-jbang-catalog.sh

   # Or specify version explicitly
   ./scripts/update-jbang-catalog.sh v0.13.0
   ```
6. **Commit and push catalog update**:
   ```bash
   git add jbang-catalog.json
   git commit -m "Update JBang catalog for release v0.13.0"
   git push
   ```

No manual version updates needed - everything is derived from git tags automatically.

## Icon Browser

To explore available Look and Feel icons for UI development:
- GUI browser: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil icons"`
- Console list: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil"`

Use `SwingUtil.uiIcon("IconName")` to safely load icons with automatic fallbacks.
