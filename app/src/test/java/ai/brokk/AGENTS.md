## Testing

1. We have standard mock versions of common interfaces: TestAnalyzer, TestConsoleIO, TestContextManager, TestGitRepo, TestProject. Ask the user to add these to the Workspace rather than rolling your own.
1. Tests run on Windows, MacOS, and Linux, so use Path objects instead of hardcoding paths-as-strings.
1. Prefer `ai.brokk.testutil.AssertionHelperUtil` methods when writing Analyzer tests that compare multi-block code strings.
   - Use `assertCodeEquals`/`assertCodeStartsWith`/`assertCodeEndsWith`/`assertCodeContains` for code string comparisons.
   - These helpers normalize line endings and trim outer whitespace, making tests stable across platforms and editors.
   - Avoid direct assertEquals on raw multi-line code output.
