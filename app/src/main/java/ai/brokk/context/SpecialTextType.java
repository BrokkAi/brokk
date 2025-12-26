package ai.brokk.context;

import ai.brokk.tasks.TaskList;
import ai.brokk.util.Json;
import java.util.Optional;
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
public enum SpecialTextType {
    BUILD_RESULTS(
            "Latest Build Results",
            SyntaxConstants.SYNTAX_STYLE_NONE,
            SyntaxConstants.SYNTAX_STYLE_NONE,
            true // droppable
            ) {
        @Override
        public String renderPreview(String rawContent) {
            return rawContent;
        }

        @Override
        public boolean canViewContent(ViewingPolicy policy) {
            return true;
        }
    },

    SEARCH_NOTES(
            "Code Notes",
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
            true // droppable
            ) {
        @Override
        public String renderPreview(String rawContent) {
            return rawContent;
        }

        @Override
        public boolean canViewContent(ViewingPolicy policy) {
            return true;
        }
    },

    DISCARDED_CONTEXT(
            "Discarded Context",
            SyntaxConstants.SYNTAX_STYLE_JSON,
            SyntaxConstants.SYNTAX_STYLE_JSON,
            false // non-droppable; protects audit log
            ) {
        @Override
        public String renderPreview(String rawContent) {
            return rawContent;
        }

        @Override
        public boolean canViewContent(ViewingPolicy policy) {
            return true;
        }
    },

    TASK_LIST(
            "Task List",
            SyntaxConstants.SYNTAX_STYLE_JSON, // internal storage is JSON
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN, // preview as Markdown
            false // non-droppable
            ) {
        @Override
        public String renderPreview(String rawContent) {
            try {
                var data = Json.getMapper().readValue(rawContent, TaskList.TaskListData.class);
                int total = data.tasks().size();
                int completed = (int)
                        data.tasks().stream().filter(TaskList.TaskItem::done).count();

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
                String quoted =
                        rawContent.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\n> ");
                return """
                        ### Task List

                        _Unable to parse saved task list. Showing raw content below for reference._

                        > %s
                        """
                        .stripIndent()
                        .formatted(quoted);
            }
        }

        @Override
        public boolean canViewContent(ViewingPolicy policy) {
            return policy.useTaskList();
        }
    };

    private final String description;
    private final String syntaxStyle;
    private final String previewSyntaxStyle;
    private final boolean droppable;

    SpecialTextType(String description, String syntaxStyle, String previewSyntaxStyle, boolean droppable) {
        this.description = description;
        this.syntaxStyle = syntaxStyle;
        this.previewSyntaxStyle = previewSyntaxStyle;
        this.droppable = droppable;
    }

    public abstract String renderPreview(String rawContent);

    public abstract boolean canViewContent(ViewingPolicy policy);

    // --- Lookups and helpers ---

    public static Optional<SpecialTextType> fromDescription(@Nullable String description) {
        if (description == null) return Optional.empty();
        for (SpecialTextType type : values()) {
            if (type.description.equals(description)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
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

    @Override
    public String toString() {
        return "SpecialTextType{" + description + "}";
    }
}
