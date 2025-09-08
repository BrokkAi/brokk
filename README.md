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
1. Follow the instructions to install jbang and run Brokk

# Documentation

Brokk documentation is at https://brokk.ai/documentation/.

# Contributing

Brokk uses Gradle with Scala support. To build Brokk,
1. Ensure you have JDK 21 or newer
2. Run Gradle commands directly: `./gradlew <command>`
3. Available commands: `run`, `test`, `build`, `shadowJar`, `tidy`, etc.

## Troubleshooting
1. You may get an `npm` error after clean:
```
 ./gradlew clean build
Configuration on demand is an incubating feature.

> Task :app:frontendBuild FAILED

> brokk-mop-frontend@0.1.0 build
> vite build -c vite.worker.config.mjs && vite build

node:internal/modules/esm/resolve:275
    throw new ERR_MODULE_NOT_FOUND(
          ^
```
To fix it run:
```bash
cd frontend-mop/
npm ci
```

There are documents on specific aspects of the code in [development.md](https://github.com/BrokkAi/brokk/tree/master/app/src/main/development.md).
