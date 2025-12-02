<p align="center">
  <img src="docs/brokk.png" alt="Brokk – the forge god" width="600">
</p>

## Table of Contents
- [Overview](#overview)
- [Running Brokk](#running-brokk)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Brokk Versioning](#brokk-versioning)
- [Increasing JVM heap when running via Gradle](#increasing-jvm-heap-when-running-via-gradle)

# Overview

Brokk (the [Norse god of the forge](https://en.wikipedia.org/wiki/Brokkr))
is the first code assistant that understands code semantically, not just
as chunks of text.  Brokk is designed to allow LLMs to work effectively
on large codebases that cannot be jammed entirely into working context.

There is a [Brokk Discord](https://discord.gg/QjhQDK8kAj) for questions and suggestions.

# Running Brokk

1. Sign up at [Brokk.ai](https://brokk.ai/)
1. Follow the instructions to download and run Brokk

# Documentation

Brokk documentation is at https://brokk.ai/documentation/.

- [Headless Executor CLI](docs/headless-exec-cli.md) — Command-line tool to start a local executor, create a session, submit a job, and stream results. Includes examples for ASK, CODE, ARCHITECT, and LUTZ.

# Contributing

Brokk uses Gradle with Scala support. To build Brokk,
1. Ensure you have JDK 21 or newer. Note the JetBrains Runtime is the preferred JDK.
2. Run Gradle commands directly: `./gradlew <command>`
3. Available commands: `run`, `test`, `build`, `shadowJar`, `tidy`, etc.

The frontend uses **pnpm** for package management. Gradle automatically handles pnpm installation and dependency management during builds.

## Brokk Versioning

Brokk’s version is derived from the git tags in this repository:

- The root `build.gradle.kts` computes the Gradle `version` by calling `git describe` over tags that look like semantic versions (for example `0.14.1`).
- The computed version is cached in `build/version.txt` so subsequent builds do not need to shell out to git when the HEAD commit has not changed.
- The `app` module generates a small build-time class `ai.brokk.BuildInfo` with a public static field `version` set to this value.

You can inspect the version in different ways:

- From source checkouts, run:

  ```bash
  ./gradlew printVersion
  ```

- From inside the application code, read `BuildInfo.version`.
- When using JBang:
  - The `brokk` alias always points at the latest released JAR on GitHub.
  - Versioned aliases such as `brokk-0.14.1` pin to a specific released version.

## Increasing JVM heap when running via Gradle

When running Brokk from source with Gradle, increase the application JVM heap using standard `-Xmx` flags. The recommended approach is to set `JAVA_TOOL_OPTIONS` so the setting is inherited by the forked application JVM.

Examples:
- macOS/Linux:
  - `JAVA_TOOL_OPTIONS="-Xmx8G" ./gradlew run`
  - Or:
    - `export JAVA_TOOL_OPTIONS="-Xmx8G"`
    - `./gradlew run`
- Windows (PowerShell):
  - `$env:JAVA_TOOL_OPTIONS="-Xmx8G"; ./gradlew run`
- Windows (cmd.exe):
  - `set JAVA_TOOL_OPTIONS=-Xmx8G && gradlew run`

Notes:
- Do not use `-Dorg.gradle.jvmargs` or `GRADLE_OPTS` for application memory. These configure Gradle's own JVM and do not affect the forked application JVM.

There are documents on specific aspects of the code in [development.md](https://github.com/BrokkAi/brokk/tree/master/app/src/main/development.md).
