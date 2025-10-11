<p align="center">
  <img src="docs/brokk.png" alt="Brokk – the forge god" width="600">
</p>

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

# Contributing

Brokk uses Gradle with Scala support. To build Brokk,
1. Ensure you have JDK 21 or newer
2. Run Gradle commands directly: `./gradlew <command>`
3. Available commands: `run`, `test`, `build`, `shadowJar`, `tidy`, etc.

The frontend uses **pnpm** for package management. Gradle automatically handles pnpm installation and dependency management during builds.

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
