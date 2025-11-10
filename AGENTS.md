# Coding Style Guide

## General principles

1. **Null Away**: This project is built with Null Away: fields, parameter, and return values are non-null by default. 
Annotate exceptions to this rule with @Nullable (imported from org.jetbrains.annotations). Use requireNonNull 
(static import from java.util.Objects) when the static type is @Nullable but our code path expects it to be non-null.
Less often, it is useful to use castNonNull (static import from org.checkerframework.checker.nullness.util.NullnessUtil) 
when we can prove a value is not null but the compiler doesn't realize it, e.g. accessing get(true) in a Map 
returned by Collectors.partitioningBy. You do not need either requireNonNull or castNonNull when a field, parameter,
or return value is not annotated @Nullable.
1. **RedundantNullCheck**: Try to resolve `RedundantNullCheck` warnings by either removing the redundant null check or by annotating the reported variable or method with @Nullable (imported from org.jetbrains.annotations). Do NOT suppress the `RedundantNullCheck` warnings.
1. **@NullMarked**: Add `@org.jspecify.annotations.NullMarked` to `package-info.java` for any new Java source code packages.
1. **Java 21 features**: The codebase leverages Java features up to JDK 21. Embrace the lambdas! and also getFirst/getLast, Collectors.toList, pattern matching for instanceof, records and record patterns, etc.
1. **Prefer functional streams to manual loops**: Leverage streams for transforming collections, joining to Strings, etc.
1. **Favor Immutable Data Structures**: Prefer `List.of` and `Map.of`, as well as the Stream Collectors.
1. **Provide Comprehensive Logging**: Log relevant information using log4j, including request/response details, errors, and other important events.
1. **Use `var`**: Prefer `var` for local variable declarations. Exception: numeric types, such as `int`, `float`, etc.
1. **Use asserts to validate assumptions**: Use `assert` to validate assumptions, and prefer making reasonable assumptions backed by assert to defensive `if` checks.
1. **DRY**: Don't Repeat Yourself. Refactor similar code into a common method. But feature flag parameters are a design smell; if you would need to add flags, write separate methods instead.
1. **Parsimony**: If you can write a general case that also generates correct results in the special case (empty input, maximum size, etc), then do so. Don't write special cases unless they are necessary.
1. **Use imports**: Avoid raw, fully qualified class names unless necessary to disambiguate; otherwise import them. EXCEPTION: if you are editing from individual method sources or usages call sites, use FQ names since you can't add easily add imports.
1. **YAGNI**: Follow the principle of You Ain't Gonna Need It; implement the simplest solution that meets the requirements, unless you have specific knowledge that a more robust solution is needed to meet near-future requirements.
1. **Keep related code together**: Don't split out code into a separate function, class, or file unless it is completely self-contained or called from multiple sites. It's easier to understand a short computation inline with its context, than split out to a separate location.

## Things to avoid

1. **No Reflection**: Don't use reflection unless it's specifically requested by the user. Especially don't use reflection to work around member visibility issues; ask the user to add the necessary file to the Workspace so you can relax the visibility, instead.
1. **No Unicode**: Don't use unicode characters in code. No fancy quotes, no fancy dashes, no fancy spaces, just plain ASCII.
1. **No DI, no mocking frameworks**: these usually lead to bad patterns where you spend more time refactoring tests than actually doing useful things. instead we give interfaces default implementations (UnsupportedOperationException is fine) to minimize boilerplate in tests.
1. **No StringBuilder**: prefer joins or interpolated text blocks where possible. Use stripIndent() with text blocks.
1. **Overcautious Exception Handling**: Let unexpected exceptions propagate up where they will be logged by a global handler. Don't catch unless you have context-specific handling to apply.

## Project-specific guidelines

1. **Use ProjectFile to represent files**. String and File and Path all have issues that ProjectFile resolves. If you're dealing with files but you don't have the ProjectFile API available, stop and ask the user to provide it. DO NOT write code that reads from a File or Path unless explicitly instructed to do so.

## GUI standards

1. **Swing thread safety**: Public methods that deal with Swing components should either assert they are being run on the EDT, or wrap in SwingUtilities.invokeLater. (Prefer `SwingUtil.runOnEdt(Callable<T> task, T defaultValue)` or `SwingUtil.runOnEdt(Runnable task)` to `SwingUtilities.invokeAndWait` when blocking for the result.)
1. **Popup dialogs**: IConsoleIO (implemented by Chrome) offers `systemNotify(String message, String title, int messageType)` and `toolError(String message, String title)` methods backed by JMessageDialog, prefer these for simple "OK" notifications 
1. **Named components**: Avoid navigating component hierarchies to retrieve a specific component by index or text. Save a reference as a field instead.
1. **Buttons**: Use ai.brokk.gui.components.MaterialButton instead of JButton. Use ai.brokk.gui.components.MaterialToggleButton instead of JToggleButton.
1. **Dialogs**: When building dialogs have buttons on the bottom. Start with a primary action button such as Ok or Done. It should have the following function applied to it ai.brokk.gui.SwingUtil.applyPrimaryButtonStyle(javax.swing.AbstractButton b). Next it should have a cancel button which is a normal ai.brokk.gui.components.MaterialButton with the text Cancel.
1. **Notifications**: Use IConsoleIO.showNotification for informational messages, and IConsoleIO.toolError for modal errors. If you do not have the IConsoleIO API available in the Workspace, stop and ask the user to provide it.
