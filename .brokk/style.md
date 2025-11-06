Here's a concise coding style guide based on the provided Java code, focusing on conventions that leverage new or uncommon features and specific codebase practices.

---

# Coding Style Guide

This guide outlines the preferred coding conventions, emphasizing modern Java features and patterns specific to this codebase.

## 1. Java Language Features

*   **Text Blocks (`"""..."""`)**: Use text blocks for multi-line strings, especially for prompts, system messages, and any longer descriptive text. Combine with `.formatted()` for dynamic content.
    ```java
    new SystemMessage(
        """
        <instructions>
        You are the Search Agent.
        Your job is to be the **Code Agent's preparer**.
        ...
        </instructions>
        """.formatted(supportedTypes, reminder)
    );
    ```
*   **Switch Expressions (`switch` with `->`)**: Prefer `switch` expressions for concise conditional logic that returns a value.
    ```java
    return switch (toolName) {
        case "answer", "createTaskList" -> ToolCategory.TERMINAL;
        case "dropWorkspaceFragments" -> ToolCategory.WORKSPACE_HYGIENE;
        default -> ToolCategory.RESEARCH;
    };
    ```
*   **Records (`record`)**: Use Java records for immutable data carriers where the primary purpose is to hold data.
    ```java
    private record TerminalObjective(String type, String text) {}
    ```
*   **`var` Keyword**: Use `var` for local variable declarations when the type is immediately obvious from the initializer, enhancing readability and reducing boilerplate.
    ```java
    var wst = new WorkspaceTools(context);
    var toolSpecs = tr.getTools(allAllowed);
    ```
*   **Numeric Literals with Underscores**: Use underscores to improve the readability of large numeric literals.
    ```java
    private static final int SUMMARIZE_THRESHOLD = 1_000;
    ```

## 2. Annotations and Framework Specifics

*   **AI Agent Tooling (`@Tool`, `@P`)**: All methods exposed as tools for AI agents **must** be `public` and annotated with `@Tool`. Parameters should be annotated with `@P` for clear descriptions.
    ```java
    @Tool("Provide a final answer to a purely informational request.")
    public String answer(
            @P("Comprehensive explanation that answers the query.")
            String explanation) {
        // ...
    }
    ```
*   **Nullability Annotations (`@Nullable`)**: Use `org.jetbrains.annotations.Nullable` to explicitly mark parameters or return types that can legitimately be null.
    ```java
    public String callMcpTool(
            String toolName,
            @Nullable Map<String, Object> arguments) {
        // ...
    }
    ```

## 3. Code Structure and Readability

*   **Custom Import Order**: Imports are grouped and ordered as follows:
    1.  Project-specific imports (`ai.brokk.*`)
    2.  External library imports (e.g., `com.fasterxml.*`, `dev.langchain4j.*`, `org.apache.*`, `org.jetbrains.*`)
    3.  Standard JDK imports (`java.*`)
*   **Static Imports**: Use static imports for frequently used utility methods, particularly `java.util.Objects.requireNonNull`.
    ```java
    import static java.util.Objects.requireNonNull;
    // ...
    var summarizeModel = requireNonNull(cm.getService().getModel(summarizeConfig));
    ```
*   **Class/Method Grouping Comments**: In large classes, use `// ======================= ... =======================` style comments to visually group related methods, improving navigation and understanding.
*   **Constructor Parameter Formatting**: For constructors with a large number of parameters, each parameter should be placed on a new line for improved readability.
    ```java
    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            Set<Terminal> allowedTerminals,
            ContextManager.TaskScope scope) {
        // ...
    }
    ```
*   **Defensive Programming**: Explicitly use `Objects.requireNonNull` to validate essential dependencies or arguments at key points (e.g., constructor, method entry points) to ensure robust behavior and fast failures.

## 4. Application-Specific Patterns

*   **Agent Task Result Handling**: Instead of throwing exceptions for expected control flow (e.g., task completion, planned abortion), agents should return `TaskResult` objects with appropriate `StopDetails` to indicate the outcome of an execution cycle. Exceptions are reserved for truly unexpected or unrecoverable errors.