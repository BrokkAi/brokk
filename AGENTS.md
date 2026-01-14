# Brokk Coding Guide

## Null Safety

1. **Null Away**: This project is built with Null Away: fields, parameter, and return values are non-null by default. 
Annotate exceptions to this rule with @Nullable (imported from org.jetbrains.annotations). Use requireNonNull 
(static import from java.util.Objects) when the static type is @Nullable but our code path expects it to be non-null.
Less often, it is useful to use castNonNull (static import from org.checkerframework.checker.nullness.util.NullnessUtil) 
when we can prove a value is not null but the compiler doesn't realize it, e.g. accessing get(true) in a Map 
returned by Collectors.partitioningBy. You do not need either requireNonNull or castNonNull when a field, parameter,
or return value is not annotated @Nullable.
1. **RedundantNullCheck**: Try to resolve `RedundantNullCheck` warnings by either removing the redundant null check or by annotating the reported variable or method with @Nullable (imported from org.jetbrains.annotations). Do NOT suppress the `RedundantNullCheck` warnings.
1. **@NullMarked**: Add `@org.jspecify.annotations.NullMarked` to `package-info.java` for any new Java source code packages.
1. **Optional**: Prefer returning Optional from methods to @Nullable; the opposite is generally the case for method parameters.

## General Principles

1. **Java 21 features**: The codebase leverages Java features up to JDK 21. Embrace the lambdas! and also getFirst/getLast, Collectors.toList, pattern matching for instanceof, records and record patterns, etc.
1. **Prefer functional streams to manual loops**: Leverage streams for transforming collections, joining to Strings, etc.
1. **Favor Immutable Data Structures**: Prefer `List.of` and `Map.of`, as well as the Stream Collectors.
1. **Provide Comprehensive Logging**: Log relevant information using log4j, including request/response details, errors, and other important events.
1. **@Blocking and EDT safety**: Annotate public methods that may block (I/O, analyzer work, network, filesystem, or other expensive computation) with `org.jetbrains.annotations.Blocking`. On the Swing Event Dispatch Thread (EDT), do not invoke `@Blocking` methods; prefer the non-blocking `computed*` alternatives (e.g., `computedFiles()`, `computedSources()`, `computedText()`, `computedDescription()`, `computedSyntaxStyle()`) to keep the UI responsive. An Error Prone check (`BrokkBlockingOperation`) enforces this and will warn if an `@Blocking` method is called on the EDT (e.g., inside `SwingUtilities.invokeLater(...)` or the true branch of an `isEventDispatchThread()`/`isDispatchThread()` check). Fix by moving the call off the EDT or by using the appropriate `computed*` method; do not suppress the warning.
1. **Use asserts to validate assumptions**: Use `assert` to validate assumptions, and prefer making reasonable assumptions backed by assert to defensive `if` checks.
1. **DRY**: Don't Repeat Yourself. Refactor similar code into a common method. But feature flag parameters are a design smell; if you would need to add flags, write separate methods instead.
1. **Parsimony**: If you can write a general case that also generates correct results in the special case (empty input, maximum size, etc), then do so. Don't write special cases unless they are necessary.
1. **Use imports**: Avoid raw, fully qualified class names unless necessary to disambiguate; otherwise import them. EXCEPTION: if you are editing from individual method sources or usages call sites, use FQ names since you can't add easily add imports.
1. **YAGNI**: Follow the principle of You Ain't Gonna Need It; implement the simplest solution that meets the requirements, unless you have specific knowledge that a more robust solution is needed to meet near-future requirements.
1. **Keep related code together**: Don't split out code into a separate function, class, or file unless it is completely self-contained or called from multiple sites. It's easier to understand a short computation inline with its context, than split out to a separate location.
1. **Prefer unordered sets**: use HashSet and Collectors.toSet unless we specifically need ordering.

## Working with LLMs

1. **Tool Calls**: If you're making tool calls, make sure you have summaries of ToolRegistry and ToolExecutionResult in the Workspace.
Note that ToolRegistry::executeTool catches exceptions and turns them into TER with INTERNAL_ERROR status, so wrapping executeTool
with try/catch is unnecessary and futile; don't do that.

## Things to avoid

1. **No Reflection**: Don't use reflection unless it's specifically requested by the user. Especially don't use reflection to work around member visibility issues; ask the user to add the necessary file to the Workspace so you can relax the visibility, instead.
1. **No Unicode**: Don't use unicode characters in code. No fancy quotes, no fancy dashes, no fancy spaces, just plain ASCII.
1. **No DI, no mocking frameworks**: these usually lead to bad patterns where you spend more time refactoring tests than actually doing useful things. instead we give interfaces default implementations (UnsupportedOperationException is fine) to minimize boilerplate in tests.
1. **No StringBuilder**: prefer joins or interpolated text blocks where possible. Use stripIndent() with text blocks.
1. **No overcautious Exception Handling**: Don't catch exceptions unless you have context-specific handling to apply; otherwise, let exceptions propagate up where they will be logged by a global handler.

## Project-specific guidelines

1. **Use ProjectFile to represent files**. String and File and Path all have issues that ProjectFile resolves. If you're dealing with files but you don't have the ProjectFile API available, stop and ask the user to provide it. DO NOT write code that reads from a File or Path unless explicitly instructed to do so.

## Testing

1. Prefer `ai.brokk.testutil.AssertionHelperUtil` methods when writing analyzer tests that compare multi-block code strings.
   - Use `assertCodeEquals`/`assertCodeStartsWith`/`assertCodeEndsWith`/`assertCodeContains` for code string comparisons.
   - These helpers normalize line endings and trim outer whitespace, making tests stable across platforms and editors.
   - Avoid direct assertEquals on raw multi-line code output.
2. We have standard mock versions of common interfaces: TestAnalyzer, TestConsoleIO, TestContextManager, TestGitRepo, TestProject. Ask the user to add these to the Workspace rather than rolling your own.

## Concurrency

1. Always use utility classes that log exceptions appropriately. If you create an ExecutorService, you
   should wrap it in a ai.brokk.concurrent.LoggingExecutorService as follows:
   ```
   ExecutorService delegateExecutor = ...;
   Consumer<Throwable> exceptionHandler = th -> GlobalExceptionHandler.handle(th, st -> {});
   this.executor = new LoggingExecutorService(delegateExecutor, exceptionHandler);
   ```
   The lambda (`String st -> {}`) is for notifying the user of problems, if you have an appropriate API avaiable then
   you should wire that up as well.
   There are convenience methods for newVirtualThreadExecutor and newFixedThreadExecutor in ai.brokk.concurrent.ExecutorsUtil
   that you should use unless you need to roll a custom ThreadFactory.
2. Use LoggingFuture.supplyAsync instead of CompletableFuture.supplyAsync; the API is the same.
3. Avoid SwingWorker in favor of virtual threads using ExecutorsUtil.newVirtualThreadExecutor, or LoggingFuture.supplyAsync.
