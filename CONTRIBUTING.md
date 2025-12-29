# Contributing to Brokk

Thank you for your interest in contributing. This guide covers environment setup, build/test commands, coding standards, and helpful docs.

## Prerequisites

- JDK 25 or newer (JetBrains Runtime recommended).
- macOS, Windows, or Linux.

## Quick start

```bash
./gradlew run        # run the app
./gradlew test       # run tests
./gradlew build      # full build (compile + test + checks)
./gradlew shadowJar  # build fat JAR
./gradlew tidy       # format Java code (spotlessApply)
```

For JVM heap when running via Gradle, see the "Increasing JVM heap when running via Gradle" section below.

## Frontend

The frontend uses pnpm and Vite. Gradle manages pnpm automatically during builds.

For development-only workflows and more details, see the development guide:
- app/src/main/development.md

## Static analysis and formatting

- Fast analysis (NullAway + spotless only, no tests):
  ```bash
  ./gradlew analyze
  ```
- Full verification (tests + Error Prone + NullAway + spotless):
  ```bash
  ./gradlew check
  ```

Install a pre-push hook to enforce formatting and analysis locally:
```bash
# Option 1: heredoc
cat > .git/hooks/pre-push << 'EOF'
#!/bin/sh
echo "Running static analysis (NullAway + spotless)..."
./gradlew analyze spotlessCheck
EOF
chmod +x .git/hooks/pre-push

# Option 2: one-liner
echo '#!/bin/sh\necho "Running static analysis (NullAway + spotless)..."\n./gradlew analyze spotlessCheck' > .git/hooks/pre-push && chmod +x .git/hooks/pre-push
```

## Build and developer docs

The comprehensive development guide includes:
- Gradle tasks, caching, and performance tips
- Testing strategy and reports
- Dependency management and analysis
- Debugging configuration
- jDeploy packaging and release process
- Theme system and asset generation

See:
- app/src/main/development.md

## Build from source

Ensure JDK 25+ is installed. Use the quick start commands above to run, test, and build. For deeper tasks (caching, frontend build, debugging, packaging, releases), see app/src/main/development.md.

## Increasing JVM heap when running via Gradle

When running Brokk from source with Gradle, increase the application JVM heap using standard -Xmx flags. The recommended approach is to set JAVA_TOOL_OPTIONS so the setting is inherited by the forked application JVM.

Examples:
- macOS/Linux:
  - JAVA_TOOL_OPTIONS="-Xmx8G" ./gradlew run
  - Or:
    - export JAVA_TOOL_OPTIONS="-Xmx8G"
    - ./gradlew run
- Windows (PowerShell):
  - $env:JAVA_TOOL_OPTIONS="-Xmx8G"; ./gradlew run
- Windows (cmd.exe):
  - set JAVA_TOOL_OPTIONS=-Xmx8G && gradlew run

Notes:
- Do not use -Dorg.gradle.jvmargs or GRADLE_OPTS for application memory. These configure Gradle's own JVM and do not affect the forked application JVM.

## Headless executor

- Overview: docs/headless-executor.md
- CLI usage: docs/headless-exec-cli.md
- Event model: docs/headless-executor-events.md

## Coding standards

- Java 21 features are welcomed (records, pattern matching, text blocks).
- Nullness: fields, parameters, and return values are non-null by default (NullAway). Use @Nullable where appropriate.
- Avoid calling blocking operations on the Swing EDT. Prefer computed* APIs for UI paths.
- Prefer streams for collection transformations; avoid defensive null checks when static types are non-null.
- Prefer immutable data structures (List.of, Map.of) and functional style for transformations.

## Submitting changes

- Keep pull requests focused and small when possible.
- Include context in the description and link related issues.
- Ensure `./gradlew check` passes locally before opening a PR.

Thank you for contributing!
