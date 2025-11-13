package ai.brokk.context;

import ai.brokk.TaskResult;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * Registry for special text fragments with centralized policy:
 * - description: human-readable type label used in existing contexts
 * - internalSyntaxStyle: how raw content is stored for serialization/highlighting
 * - droppable: whether WorkspaceTools may remove this fragment
 * - singleton: ensure only one instance exists across the context
 * - previewRenderer: transforms raw content into a UI-friendly preview (e.g., Markdown)
 * - canViewContent: predicate to determine if a given task type can see full content (for redaction)
 *
 * This does not change serialization by itself; it centralizes policy that other code can consult.
 */
public final class SpecialTextType {
    private static final Map<String, SpecialTextType> BY_DESCRIPTION = new LinkedHashMap<>();

    private final String description;
    private final String internalSyntaxStyle;
    private final String previewSyntaxStyle;
    private final boolean droppable;
    private final boolean singleton;
    private final Function<String, String> previewRenderer;
    private final Function<String, Optional<Map<String, Object>>> modelExtractor;
    private final Predicate<TaskResult.Type> canViewContent;

    private SpecialTextType(
            String description,
            String internalSyntaxStyle,
            String previewSyntaxStyle,
            boolean droppable,
            boolean singleton,
            Function<String, String> previewRenderer,
            Function<String, Optional<Map<String, Object>>> modelExtractor,
            Predicate<TaskResult.Type> canViewContent) {
        this.description = description;
        this.internalSyntaxStyle = internalSyntaxStyle;
        this.previewSyntaxStyle = previewSyntaxStyle;
        this.droppable = droppable;
        this.singleton = singleton;
        this.previewRenderer = previewRenderer;
        this.modelExtractor = modelExtractor;
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
            SyntaxConstants.SYNTAX_STYLE_NONE, // preview style
            true, // droppable
            true, // singleton
            Function.identity(), // raw preview is fine
            s -> Optional.empty(), // no structured model by default
            t -> true // visible to all agents by default
            ));

    public static final SpecialTextType SEARCH_NOTES = register(new SpecialTextType(
            "Code Notes",
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN, // preview style
            true, // droppable
            true, // singleton
            Function.identity(), // already Markdown
            s -> Optional.empty(), // no structured model by default
            t -> true // visible to all
            ));

    public static final SpecialTextType DISCARDED_CONTEXT = register(new SpecialTextType(
            "Discarded Context",
            SyntaxConstants.SYNTAX_STYLE_JSON,
            SyntaxConstants.SYNTAX_STYLE_JSON, // preview style
            false, // non-droppable; protects audit log
            true, // singleton
            Function.identity(), // JSON preview by default
            s -> {
                try {
                    var mapper = Json.getMapper();
                    Map<String, Object> map = mapper.readValue(s, new TypeReference<>() {
                    });
                    return Optional.of(map);
                } catch (Exception e) {
                    return Optional.empty();
                }
            }, // structured model: parsed JSON map
            t -> true // visible to all
            ));

    public static final SpecialTextType TASK_LIST = register(new SpecialTextType(
            "Task List",
            SyntaxConstants.SYNTAX_STYLE_JSON, // internal storage as JSON
            SyntaxConstants.SYNTAX_STYLE_MARKDOWN, // preview as Markdown
            false, // non-droppable
            true, // singleton
            Function.identity(), // preview already Markdown-friendly
            s -> Optional.empty(), // model can be provided later (e.g., JSON->Map)
            t -> true // default visibility; callers may apply redaction policy
            ));

    // --- Lookups and helpers ---

    public static Optional<SpecialTextType> fromDescription(String description) {
        if (description == null) return Optional.empty();
        return Optional.ofNullable(BY_DESCRIPTION.get(description));
    }

    public static boolean isSpecialDescription(String description) {
        return fromDescription(description).isPresent();
    }

    public static boolean isDroppable(String description) {
        return fromDescription(description).map(SpecialTextType::droppable).orElse(true);
    }

    public static boolean isSingleton(String description) {
        return fromDescription(description).map(SpecialTextType::singleton).orElse(false);
    }

    public static Collection<SpecialTextType> values() {
        return BY_DESCRIPTION.values();
    }

    // --- Accessors ---

    public String description() {
        return description;
    }

    public String internalSyntaxStyle() {
        return internalSyntaxStyle;
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

    public Function<String, Optional<Map<String, Object>>> modelExtractor() {
        return modelExtractor;
    }

    public Predicate<TaskResult.Type> canViewContent() {
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
}
