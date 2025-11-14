# Development Guide

## Project Structure

This is a multi-module Gradle project with Kotlin DSL:

- **`analyzer-api`** - Core analyzer interfaces and types (1% of codebase, compiled with javac)
- **`app`** - Java 21 main application with TreeSitter analyzers (94% of codebase, compiled with javac)

### Module Dependency Diagram

```
┌───────────────────────────────────────────────────────────┐
│                    Module Dependencies                    │
└───────────────────────────────────────────────────────────┘

                    ┌──────────────────────┐
                    │        app           │
                    │                      │
                    │ • GUI Application    │
                    │ Compiler: javac      │
                    └──────────────────────┘
                             │
                             │ implementation
                             ▼
                    ┌─────────────────┐
                    │  analyzer-api   │
                    │                 │
                    │ Compiler: javac │
                    └─────────────────┘

Dependencies:
• app → analyzer-api
• analyzer-api → (no dependencies)
```

## Essential Gradle Tasks for New Developers

### Quick Start
- `./gradlew run` - Run the application (from app) without debugging
- `./gradlew run -PenableDebug=true` - Run with debugging enabled on port 5005
- `./gradlew build` - Full build (compile, test, check) - all modules + frontend
- `./gradlew assemble` - Build without tests - all modules + frontend

### Development Workflow - All Projects
- `./gradlew clean` - Clean build artifacts for all modules + frontend
- `./gradlew classes` - Compile all main sources (fastest for development)
- `./gradlew shadowJar` - Create fat JAR for distribution (explicit only)
- `./gradlew generateThemeCss` - Generate theme CSS variables from ThemeColors.java

### Frontend Build Tasks

#### Gradle Tasks (Recommended)
- `./gradlew frontendBuild` - Build frontend with Vite (includes pnpm install)
- `./gradlew frontendClean` - Clean frontend build artifacts and node_modules

#### Direct pnpm Commands (Development)
For frontend-only development, you can work directly in the `frontend-mop/` directory:

```bash
cd frontend-mop/

# Use Gradle-installed pnpm (recommended)
alias pnpm='../.gradle/pnpm/pnpm-v9.15.4/bin/pnpm'

# Install dependencies
pnpm install

# Development server with hot reload
pnpm run dev

# Production build (outputs to app/src/main/resources/mop-web/)
pnpm run build

# Preview production build
pnpm run preview
```

**Note**: The project uses **pnpm** (not npm) for faster installs and better disk usage. The `build` script runs both worker and main builds via Vite. Gradle automatically handles pnpm via the `pnpmInstall` task. Configuration is in `frontend-mop/.npmrc` (shamefully-hoist mode for compatibility).

### Development Workflow - Individual Projects
- `./gradlew :analyzer-api:compileJava` - Compile API interfaces only
- `./gradlew :app:compileJava` - Compile Java code only
- `./gradlew :analyzer-api:classes` - Compile analyzer-api (Java)
- `./gradlew :app:classes` - Compile app (Java)
- `./gradlew :analyzer-api:assemble` - Build API module only
- `./gradlew :app:assemble` - Build app project only

### Testing
- `./gradlew test` - Run all tests (includes TreeSitter and regular tests)
- `./gradlew check` - Run all checks (tests + static analysis)

#### Running Tests by Project
- `./gradlew :analyzer-api:test` - Run tests only in the API module
- `./gradlew :app:test` - Run tests only in the app Java project

#### Running Test Subsets
- `./gradlew test --tests "*AnalyzerTest"` - Run all analyzer tests (includes TreeSitter)
- `./gradlew test --tests "*.EditBlockTest"` - Run specific test class
- `./gradlew test --tests "*EditBlock*"` - Run tests matching pattern
- `./gradlew test --tests "ai.brokk.git.*"` - Run tests in package
- `./gradlew test --tests "*TypescriptAnalyzerTest"` - Run TreeSitter analyzer tests

#### Project-Specific Test Patterns
- `./gradlew :analyzer-api:test --tests "*Analyzer*"` - Run API interface tests
- `./gradlew :app:test --tests "*GitRepo*"` - Run Git-related tests in app project

#### Test Configuration Notes
- **Unified test suite**: All tests run together in a single forked JVM
- **Single fork strategy**: One JVM fork for the entire test run
- **Native library isolation**: TreeSitter tests safely isolated while maintaining good performance

#### Test Reports
After running tests, detailed reports are automatically generated:
- **All Projects**: `build/reports/tests/test/index.html` - Combined test results
- **analyzer-api**: `analyzer-api/build/reports/tests/test/index.html` - API module tests
- **app**: `app/build/reports/tests/test/index.html` - Java project tests
- **Console Output**: Real-time test progress with pass/fail/skip status

### Code Formatting

#### Java Code (app, analyzer-api)
- `./gradlew tidy` - Format all Java code. (Alias for `./gradlew spotlessApply`)
- `./gradlew spotlessCheck` - Check if code formatting is correct (CI-friendly)
- `./gradlew spotlessInstallGitPrePushHook` - Install pre-push hook for automatic formatting checks

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

The build uses ErrorProne for compile-time bug detection with conditional NullAway analysis:

#### ErrorProne & NullAway

- **Regular builds** (`./gradlew build`, `test`): Fast - ErrorProne completely disabled (0% overhead)
- **Full analysis** (`./gradlew check`): Slow - includes ErrorProne + NullAway dataflow analysis (~20-50% overhead)

#### Running Static Analysis Locally

```bash
# Fast: NullAway + spotless only (no tests, ~1-2 min)
./gradlew analyze

# Slow: Full verification with tests (complete CI validation)
./gradlew check
```

#### Git Hook Setup (Pre-Push)

To automatically run static analysis before pushing (recommended):

```bash
# Method 1: Multi-line heredoc (copy all lines together)
cat > .git/hooks/pre-push << 'EOF'
#!/bin/sh
echo "Running static analysis (NullAway + spotless)..."
./gradlew analyze spotlessCheck
EOF
chmod +x .git/hooks/pre-push

# Method 2: One-liner (easier to copy-paste)
echo '#!/bin/sh\necho "Running static analysis (NullAway + spotless)..."\n./gradlew analyze spotlessCheck' > .git/hooks/pre-push && chmod +x .git/hooks/pre-push
```

The pre-push hook will block the push if any errors are found, ensuring code quality before sharing changes.

#### Usage Summary

- `./gradlew build` → Fast compilation, no static analysis overhead
- `./gradlew test` → Fast tests, no static analysis overhead
- `./gradlew analyze` → Static analysis only (ErrorProne + NullAway + spotless, no tests)
- `./gradlew check` → Full verification (tests + ErrorProne + NullAway + spotless)
- CI should run `check` for complete validation

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
- Use `./gradlew classes` to compile both projects
- Use `./gradlew assemble` for development (skips tests, no JARs)
- Configuration cache automatically optimizes repeated builds
- Compiler uses 2GB heap with G1GC for faster compilation
- File system watching enabled for better incremental builds
- Frontend build uses Gradle cache and incremental compilation for faster rebuilds

### Debugging Configuration

Debugging is disabled by default and must be explicitly enabled when needed. This prevents conflicts with IDE debuggers.

#### Debugging Options
- **Default**: `./gradlew run` - Run without debugging
- **Enable debugging**: `./gradlew run -PenableDebug=true` - Enable JDWP debugging on port 5005
- **Custom port**: `./gradlew run -PenableDebug=true -PdebugPort=8000` - Use different debug port
- **IntelliJ integration**: Debug normally from IntelliJ - no conflicts with Gradle

#### Multiple Instances
To run multiple instances simultaneously, enable debugging only on specific instances:
```bash
# Instance 1 without debugging
./gradlew run

# Instance 2 with debugging on port 5005
./gradlew run -PenableDebug=true

# Instance 3 with debugging on custom port
./gradlew run -PenableDebug=true -PdebugPort=5006
```

### JAR Creation
- **Development builds** (`build`, `assemble`) skip JAR creation for speed
- **Explicit JAR creation**: `./gradlew shadowJar` when needed
- **CI/Release builds**: `CI=true ./gradlew build` includes JAR automatically
- **Force JAR in build**: `./gradlew -PenableShadowJar build`
- **Frontend assets**: Automatically included in JAR under `mop-web/` resources

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

## Distribution with jDeploy

The project uses jDeploy to create native application installers for all platforms. jDeploy packages the shadow JAR into platform-specific installers and handles distribution via npm.

### jDeploy Configuration

Configuration is defined in the root `package.json`:
- **JAR Source**: `app/build/libs/brokk-*.jar` (shadow JAR)
- **Java Version**: 21 (required)
- **JavaFX**: Enabled for GUI support
- **JVM Args**: `--add-modules jdk.incubator.vector` for Vector API
- **No Bundled JDK**: Uses system Java installation

### Local jDeploy Usage

```bash
# 1. Build the shadow JAR first
./gradlew shadowJar

# 2. Get the current git version (same as CI does)
VERSION=$(git describe --tags --exact-match HEAD 2>/dev/null || git describe --tags --always --dirty=-SNAPSHOT)

# 3. Update both version and JAR path in package.json
JAR_FILE=$(find app/build/libs -name "brokk-*.jar" | head -1)
jq '.version = "'$VERSION'" | .jdeploy.jar = "'$JAR_FILE'"' package.json > package.tmp.json && mv package.tmp.json package.json

# 4. Install jDeploy if not installed
npm install -g jdeploy

# 5. Package with jDeploy
jdeploy package

# 6. Test the packaged application
./jdeploy-bundle/jdeploy.js

# Alternative: Install locally for testing (links to PATH)
jdeploy install
# Then run from anywhere: brokk

# Alternative: Test JAR directly without jDeploy
java --add-modules jdk.incubator.vector -jar app/build/libs/brokk-*.jar

# Optional: Build native installers locally
# Define your target platforms (customize as needed):
TARGETS="mac-x64,mac-arm64,win,linux"  # Available: mac-x64, mac-arm64, win, linux

# Add bundles/installers to package.json:
jq --argjson targets "$(echo $TARGETS | jq -R 'split(",")')" '.jdeploy.bundles = $targets | .jdeploy.installers = $targets' package.json > package.tmp.json && mv package.tmp.json package.json

# Then package with installers
jdeploy package

# Generated files will be in:
# - jdeploy/bundles/ - Platform-specific app bundles for selected targets
# - jdeploy/installers/ - Native installers for selected targets
```

### Release Process Integration

jDeploy runs automatically in CI/CD during releases:

1. **Triggered by**: Git tag pushes (`v1.0.0`, `1.0.0-beta`, etc.)
2. **Workflows**: Both `jdeploy.yml` and `release.yml` workflows
3. **Process**:
   - Build shadow JAR with Gradle
   - Update `package.json` with exact JAR filename
   - Run jDeploy to create installers
   - Upload installers as GitHub release assets

### Prerelease Handling

jDeploy detects pre-releases based on semantic versioning:
- **Prerelease**: `1.0.0-alpha`, `1.0.0-beta.1`, `1.0.0-rc.1`
- **Stable**: `1.0.0`, `2.1.0`

Prereleases are marked appropriately in GitHub releases and distribution channels.

### Development Builds

Automatic development builds are created by the `jdeploy.yaml` workflow on every commit to the `master` branch. These builds allow developers to test the latest changes without waiting for an official release.

**Accessing Development Builds:**
- Development builds are available at: https://github.com/BrokkAi/brokk/releases/tag/master-snapshot
- The `master` tag is automatically updated after each commit to `master`
- Platform-specific installers (macOS, Windows, Linux) are available for download

**Auto-Update Behavior:**
- If installed with auto-update enabled, the application will download the latest build each time it's launched
- This keeps the development version synchronized with the current state of the `master` branch
- Version numbers in development builds include git commit information (e.g., `0.12.1-30-g77dcc897`)

## Performance Baseline Testing

The TreeSitterRepoRunner provides comprehensive performance analysis for TreeSitter analyzers across major open source projects.

For complete documentation, see [baseline-testing.md](baseline-testing.md).

### Quick Start
```bash
# Get help and see available commands
scripts/run-treesitter-repos.sh help

# Set up test projects (downloads major projects)
scripts/run-treesitter-repos.sh setup

# Test specific project with specific language
scripts/run-treesitter-repos.sh openjdk-java --max-files 500
```

## Icon Browser

To explore available Look and Feel icons for UI development:
- GUI browser: `./gradlew run --args="ai.brokk.gui.SwingIconUtil icons"`
- Console list: `./gradlew run --args="ai.brokk.gui.SwingIconUtil"`

Use `SwingUtil.uiIcon("IconName")` to safely load icons with automatic fallbacks.

## Theme System

The application uses a unified theme system that synchronizes colors between Java Swing components and the frontend web interface.

### Theme Generation Workflow

1. **Java Theme Definition**: Colors are defined in `ThemeColors.java` with separate maps for dark and light themes
2. **CSS Generation**: Run `./gradlew generateThemeCss` to generate CSS variables from Java colors
3. **Frontend Integration**: Generated CSS is written to `frontend-mop/src/styles/theme-colors.generated.scss`

### Key Files

- **`app/src/main/java/io/github/jbellis/brokk/gui/mop/ThemeColors.java`** - Master theme color definitions
- **`app/src/main/java/io/github/jbellis/brokk/tools/GenerateThemeCss.java`** - CSS generation tool
- **`frontend-mop/src/styles/theme-colors.generated.scss`** - Auto-generated CSS variables

### Adding New Theme Colors

1. Add color to appropriate theme map in `ThemeColors.java`:
   ```java
   DARK_COLORS.put("my_new_color", Color.decode("#123456"));
   LIGHT_COLORS.put("my_new_color", Color.decode("#654321"));
   ```

2. Regenerate CSS variables:
   ```bash
   ./gradlew generateThemeCss
   ```

3. Use in frontend SCSS:
   ```scss
   .my-component {
     color: var(--my-new-color);
   }
   ```

### Theme Naming Conventions

- Use snake_case in Java: `inline_code_color`
- Converts to kebab-case in CSS: `--inline-code-color`
- Colors are automatically sorted alphabetically in generated CSS

## Environment Variables

### BRK_USAGE_BOOL

Controls whether usage relevance classification is requested/handled as a boolean (yes/no) or as the default real-number score.

- Name: BRK_USAGE_BOOL
- Type: Boolean (case-insensitive)
- Recognized truthy values: 1, true, t, yes, y, on
- Recognized falsy values: 0, false, f, no, n, off
- Empty string: treated as true (enables boolean mode)
- Unrecognized values: defaults to false (numeric score mode) and logs a warning
- Unset: treated as false (numeric score mode)

Behavior:
- When true, the analyzer requests boolean relevance from the model and maps results to UsageHit.confidence:
  - true → confidence = 1.0
  - false → confidence = 0.0
- When false/unset, the analyzer requests a real-valued relevance score in [0.0, 1.0] (existing behavior).

APIs:
- Java:
  - ai.brokk.analyzer.usages.UsageConfig.isBooleanUsageMode() — returns boolean mode snapshot.
  - ai.brokk.agents.RelevanceClassifier.relevanceBooleanBatch(...) — batch boolean relevance.
  - Existing ai.brokk.agents.RelevanceClassifier.relevanceScoreBatch(...) remains unchanged.
- Prompting:
  - ai.brokk.analyzer.usages.UsagePromptBuilder builds prompts that include a <candidates> section of other plausible CodeUnits (excluding the target). The filter description adapts to boolean vs numeric mode accordingly.

Examples:
```bash
# Enable boolean relevance classification
export BRK_USAGE_BOOL=true

# Disable boolean classification (use numeric score)
export BRK_USAGE_BOOL=false

# Empty/invalid values
export BRK_USAGE_BOOL=""        # treated as true
export BRK_USAGE_BOOL="maybe"   # logs warning, uses numeric score mode
```

## Style Guide Aggregation (AGENTS.md)

Brokk aggregates nested AGENTS.md files to build the style guide used in prompts.

- Precedence: nearest-first per context file (walk up to the project master root). Across multiple files, sections are interleaved by depth and de-duplicated (a given AGENTS.md appears once).
- Fallback: if no AGENTS.md is found near context files, Brokk falls back to the root AGENTS.md or the legacy project style guide.
- Where to place guides: put an AGENTS.md in each subproject root you want to influence (e.g., `apps/web/AGENTS.md`, `services/api/AGENTS.md`, `packages/foo/AGENTS.md`). The resolver will pick these up automatically.
- Limits: to protect prompt budget, aggregation caps at 8 sections and ~20k characters by default. Override via system properties:
  - -Dbrokk.style.guide.maxSections=NN
  - -Dbrokk.style.guide.maxChars=NNNNN

### Blocking policy and non-blocking usage

To prevent UI stalls and unintended analyzer/I/O work on hot paths, we now use JetBrains annotations and the computed API:

- Annotate concrete synchronous methods that may block or be expensive with `@org.jetbrains.annotations.Blocking` (for example, `ContextFragment.files()`, `ContextFragment.sources()`, `PathFragment.text()`).
- Do not annotate overrides that are trivially cheap (in-memory only). If an override does no I/O and no analysis, leave it unannotated.
- Prefer the non-blocking accessors on `ContextFragment.ComputedFragment`:
  - `computedText()`, `computedDescription()`, `computedSyntaxStyle()`
  - `computedFiles()`, `computedSources()`
  These return `ComputedValue<T>` and are safe to use from the UI or other latency-sensitive code.

Implementation notes:
- Default implementations in `ComputedFragment` bridge existing code by returning a completed `ComputedValue` based on the current blocking methods (`files()/sources()/text()`), preserving backward compatibility while enabling incremental adoption of true async implementations.
- There is no Brokk-specific Error Prone checker anymore. We rely on standard Error Prone + NullAway, code review, and the `@Blocking` signal to guide call sites away from blocking the EDT.

Usage guidelines:
- Use `ComputedValue` helpers:
  - Non-blocking probes via `tryGet()` or `renderNowOr(placeholder)`
  - Bounded waits via `await(Duration)` that never block the Swing EDT
  - Asynchronous callbacks via `onComplete(...)`
- If the fragment type is not known at compile time, gate computed usage with `instanceof ContextFragment.ComputedFragment`.

Example (safe usage from UI):
```java
void showFilesLabel(ContextFragment cf, javax.swing.JLabel label) {
    if (cf instanceof ContextFragment.ComputedFragment cc) {
        cc.computedFiles().onComplete((files, error) -> {
            if (error == null && files != null) {
                javax.swing.SwingUtilities.invokeLater(() ->
                    label.setText("Files: " + files.size()));
            }
        });
    } else {
        // Fallback, avoid blocking UI: show placeholder
        label.setText("Files: …");
    }
}
```

Notes:
- Do not block the Swing EDT. `ComputedValue.await` returns empty immediately if called from the EDT.
- For quick rendering paths, use `renderNowOr("…")` to avoid stalls and update later via `onComplete`.
- When migrating legacy call sites, prefer `computed*()` accessors first; if the fragment type is not known to be a `ComputedFragment`, gate the call with an `instanceof` check, as shown above.
