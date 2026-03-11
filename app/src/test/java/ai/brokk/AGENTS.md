## Testing

1. We have standard mock versions of common interfaces: TestAnalyzer, TestConsoleIO, TestContextManager, TestGitRepo, TestProject. Ask the user to add these to the Workspace rather than rolling your own.
   TestProject also has the associated InlineTestProjectCreator convenience class. Do not roll your own ad hoc IProject implementations!
1. Tests run on Windows, MacOS, and Linux, so use Path objects instead of hardcoding paths-as-strings.
1. Prefer `ai.brokk.testutil.AssertionHelperUtil` methods when writing Analyzer tests that compare multi-block code strings.
   - Use `assertCodeEquals`/`assertCodeStartsWith`/`assertCodeEndsWith`/`assertCodeContains` for code string comparisons.
   - These helpers normalize line endings and trim outer whitespace, making tests stable across platforms and editors.
   - Avoid direct assertEquals on raw multi-line code output.

### Writing Analyzer Tests

1. **No reflection**: Do not use reflection in tests to access analyzer internals or invoke methods. If a method needs to be tested but is not accessible, ask to relax its visibility in the source file instead of using reflection to work around it.
2. **Disable failing tests, do not remove them**: If a test does not pass due to a known limitation or pending implementation, annotate it with `@Disabled("reason")` rather than deleting it. This preserves the test as documentation of expected behavior and ensures it can be re-enabled once the underlying issue is fixed.
3. **Prefer inline test projects**: When writing tests that require source code examples, prefer using `InlineTestProjectCreator` and `AnalyzerCreator` to create ephemeral test projects programmatically. Avoid adding files to test resources (`src/test/resources`) unless you are creating an explicit integration-like test that requires a realistic project structure.
