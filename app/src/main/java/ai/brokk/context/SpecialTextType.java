package ai.brokk.context;

import ai.brokk.TaskResult;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Registry for special text fragments with centralized policy:
 * - description: human-readable type label used in existing contexts
 * - syntaxStyle: how raw content is stored for serialization/highlighting
 * - droppable: whether WorkspaceTools may remove this fragment
 * - singleton: ensure only one instance exists across the context
 * - previewRenderer: transforms raw content into a UI-friendly preview (e.g., Markdown)
 * - canViewContent: predicate to determine if a given viewing policy can see full content (for redaction)
 *
 * This does not change serialization by itself; it centralizes policy that other code can consult.
 */
public final class SpecialTextType {
    private static final Map<String, SpecialTextType> BY_DESCRIPTION = new LinkedHashMap<>();

    private final String description;
    private final String syntaxStyle;
    private final String previewSyntaxStyle;
    private final boolean droppable;
    private final boolean singleton;
    private final Function<String, String> previewRenderer;
    private final Predicate<ViewingPolicy> canViewContent;

    private SpecialTextType(
            String description,
            String syntaxStyle,
            String previewSyntaxStyle,
            boolean droppable,
            boolean singleton,
            Function<String, String> previewRenderer,
            Predicate<ViewingPolicy> canViewContent) {
        this.description = description;
        this.syntaxStyle = syntaxStyle;
        this.previewSyntaxStyle = previewSyntaxStyle;
        this.droppable = droppable;
        this.singleton = singleton;
        this.previewRenderer = previewRenderer;
        this.canViewContent = canViewContent;
    }

    private static SpecialTextType register(SpecialTextType type) {
        BY_DESCRIPTION.put(type.description, type);
        return type;
    }

    // --- Registry entries ---

    public static final SpecialTextType BUILD_RESULTS = register(new SpecialTextType(
            "Latest Build Results",
            SyntaxConstants.SYNTAX_STYLE_NONE,
            SyntaxConstants.SYNTAX_STYLE_NONE,
            true, // droppable
            true, // singleton
            Function.identity(), // raw preview is fine
            v -> true // visible to all agents by default
            ));

    public static final SpecialTextType SEARCH_NOTES = register(new SpecialTextType(
            "Code Notes",
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
            true, // droppable
            true, // singleton
            Function.identity(), // already Markdown
            v -> true // visible to all
            ));

    public static final SpecialTextType DISCARDED_CONTEXT = register(new SpecialTextType(
            "Discarded Context",
            SyntaxConstants.SYNTAX_STYLE_JSON,
            SyntaxConstants.SYNTAX_STYLE_JSON,
            false, // non-droppable; protects audit log
            true, // singleton
            Function.identity(), // JSON preview by default
            v -> true // visible to all
            ));

    public static final SpecialTextType TASK_LIST = register(new SpecialTextType(
            "Task List",
            SyntaxConstants.SYNTAX_STYLE_JSON, // internal storage is JSON
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN, // preview as Markdown
            false, // non-droppable
            true, // singleton
            SpecialTextType::renderTaskListMarkdown, // render JSON → Markdown for preview
            v -> v.taskType() == TaskResult.Type.SEARCH
                    || v.taskType() == TaskResult.Type.ASK
                    || v.taskType() == TaskResult.Type.COPY // COPY used for CopyExternal prompts
            ));

    // --- Lookups and helpers ---

    public static Optional<SpecialTextType> fromDescription(@Nullable String description) {
        if (description == null) return Optional.empty();
        return Optional.ofNullable(BY_DESCRIPTION.get(description));
    }

    // --- Accessors ---

    public String description() {
        return description;
    }

    public String syntaxStyle() {
        return syntaxStyle;
    }

    public String previewSyntaxStyle() {
        return previewSyntaxStyle;
    }

    public boolean droppable() {
        return droppable;
    }

    public boolean singleton() {
        return singleton;
    }

    public Function<String, String> previewRenderer() {
        return previewRenderer;
    }

    public Predicate<ViewingPolicy> canViewContent() {
        return canViewContent;
    }

    @Override
    public String toString() {
        return "SpecialTextType{" + description + "}";
    }

    @Override
    public int hashCode() {
        return description.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof SpecialTextType other) && description.equals(other.description);
    }

    /**
     * Renders Task List JSON content as Markdown for UI preview.
     * Modern, clean formatting:
     * - Header
     * - Progress summary
     * - GitHub-style checkbox list with strikethrough for completed items
     *
     * On parse error, falls back to a readable Markdown message with a block-quoted raw payload (no code fences).
     */
    private static String renderTaskListMarkdown(String json) {
        try {
            var data = Json.getMapper().readValue(json, TaskList.TaskListData.class);
            int total = data.tasks().size();
            int completed =
                    (int) data.tasks().stream().filter(TaskList.TaskItem::done).count();

            var sb = new StringBuilder();
            sb.append("# Task List\n\n");
            sb.append("> Progress: ")
                    .append(completed)
                    .append("/")
                    .append(total)
                    .append("\n\n");

            if (total == 0) {
                sb.append("_No tasks yet._");
                return sb.toString();
            }

            for (var item : data.tasks()) {
                boolean done = item.done();
                var title = item.title();
                String text = item.text();
                boolean hasTitle = title != null && !title.isBlank();
                boolean hasText = !text.isBlank();

                sb.append(done ? "- [x] " : "- [ ] ");

                if (hasTitle) {
                    String titleMd = "**" + title + "**";
                    sb.append(done ? "~~" + titleMd + "~~" : titleMd);
                    sb.append("\n");

                    if (hasText) {
                        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
                        for (String line : normalized.split("\n", -1)) {
                            sb.append("  > ");
                            if (done) {
                                sb.append("~~").append(line).append("~~");
                            } else {
                                sb.append(line);
                            }
                            sb.append("\n");
                        }
                    }
                } else {
                    if (done) {
                        sb.append("~~").append(text).append("~~");
                    } else {
                        sb.append(text);
                    }
                    sb.append("\n");
                }
            }

            return sb.toString().stripTrailing();
        } catch (Exception e) {
            // No JSON code fence per requirement; show a readable note and block-quoted raw text.
            String quoted = json.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\n> ");
            return """
                    ### Task List

                    _Unable to parse saved task list. Showing raw content below for reference._

                    > %s
                    """
                    .stripIndent()
                    .formatted(quoted);
        }
    }
}
