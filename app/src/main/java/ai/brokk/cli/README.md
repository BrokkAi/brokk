# Brokk CLI (BprCli) Environment Variables

The Brokk CLI (`BprCli`) supports several environment variables to configure build and test execution. These variables are particularly useful for CI/CD integrations, headless environments, or overriding project-level settings.

## Build and Test Configuration

These variables control the commands used for project verification.

| Variable | Description |
| :--- | :--- |
| `BRK_BUILD_CMD` | The command used to build or lint the project (e.g., `mvn compile`). |
| `BRK_BUILDLINT_ENABLED` | Boolean (`true`/`false`) to enable or disable the build/lint command. |
| `BRK_TESTALL_CMD` | The command to run the entire test suite (e.g., `npm test`). |
| `BRK_TESTALL_ENABLED` | Boolean (`true`/`false`) to enable or disable the "Test All" command. |
| `BRK_TESTSOME_CMD` | A Mustache template used to run specific tests based on current workspace context. |
| `BRK_TESTSOME_ENABLED` | Boolean (`true`/`false`) to enable or disable the "Test Some" command. |

### Mustache Templates in `BRK_TESTSOME_CMD`

The `BRK_TESTSOME_CMD` supports Mustache tags to inject files, classes, or packages identified by Brokk as relevant to the current task.

Supported tags:
- `{{#files}}...{{/files}}`: List of relative file paths.
- `{{#classes}}...{{/classes}}`: List of simple class names.
- `{{#fqclasses}}...{{/fqclasses}}`: List of fully-qualified class names.
- `{{#packages}}...{{/packages}}`: List of package names or directory paths.
- `{{pyver}}`: Injected Python version (e.g., `3.11`).

**Example (Maven):**
```bash
export BRK_TESTSOME_CMD="mvn test -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}"
```

## Multi-Module Projects

For monorepos or multi-module projects, you can inject the entire module configuration as a JSON array.

| Variable | Description |
| :--- | :--- |
| `BRK_MODULES_JSON` | A JSON array of module definitions. |

### `BRK_MODULES_JSON` Structure

Each object in the array should contain:
- `alias`: A friendly name for the module.
- `relativePath`: Path from the project root (e.g., `libs/core`).
- `language`: The primary programming language.
- `buildLintCommand`: Module-specific build command.
- `testAllCommand`: Module-specific "Test All" command.
- `testSomeCommand`: Module-specific "Test Some" template.

**Example:**
```bash
export BRK_MODULES_JSON='[
  {
    "alias": "api",
    "relativePath": "services/api",
    "language": "Java",
    "buildLintCommand": "./gradlew :services:api:classes",
    "testAllCommand": "./gradlew :services:api:test",
    "testSomeCommand": "./gradlew :services:api:test {{#classes}}--tests {{value}}{{/classes}}"
  }
]'
```

## LLM and API Configuration

These variables configure the connection to Brokk's inference services.

| Variable | Description |
| :--- | :--- |
| `BROKK_API_KEY` | Your Brokk API key. |
| `BROKK_PROXY` | Proxy setting (`BROKK`, `LOCALHOST`, or `STAGING`). |
| `BROKK_FAVORITE_MODELS` | JSON array of favorite model configurations. |

## Context Management

| Variable | Description |
| :--- | :--- |
| `BRK_CONTEXT_CACHE` | Controls context recommendation caching (`RW`, `READ`, `WRITE`, or `OFF`). |
