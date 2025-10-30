<p align="center">
  <img src="docs/brokk.png" alt="Brokk – the forge god" width="600">
</p>

## Table of Contents
- [Overview](#overview)
- [Running Brokk](#running-brokk)
- [Documentation](#documentation)
- [Git Issue Capture tools](#git-issue-capture-tools)
- [Contributing](#contributing)
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

## Git Issue Capture tools

- addAllGithubIssuesAsFragment(repoUrl)
  - Description: Adds a compact Markdown summary (one line per issue) for all issues in the specified GitHub repository into the Workspace context.
  - Accepted repo URL examples: https://github.com/{owner}/{repo}, github.com/{owner}/{repo}, git@github.com:{owner}/{repo}.git
  - Validation: v1 only supports capturing issues from the current project's configured repository. If the provided repo does not match the current project folder name, the tool returns an error and does not capture.

- addGithubIssueAsFragment(repoUrl, issueId)
  - Description: Adds the full issue content (and comments) for the specified issue into the Workspace context. The main issue is added as a TaskFragment (formatted Markdown) and comments (if any) are added as a separate TaskFragment.
  - Accepted issue id formats: 123 or #123 (a leading '#' is tolerated and stripped automatically).
  - Validation: Same project-repo limitation as above.

Notes:
- The repository URL is parsed to extract {owner}/{repo} via GitUiUtil.parseOwnerRepoFromUrl.
- Future: cross-repo capture and stricter validation of the remote URL may be supported.

# Contributing

Brokk uses Gradle with Scala support. To build Brokk,
1. Ensure you have JDK 21 or newer. Note the JetBrains Runtime is the preferred JDK.
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
