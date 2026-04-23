package ai.brokk.analyzer.go;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import org.treesitter.GoNodeField;
import org.treesitter.GoNodeType;

/**
 * Go analyzer constants and Tree-sitter name helpers.
 *
 * <p>Node types and fields should come from the generated Tree-sitter enums ({@link GoNodeType} and {@link GoNodeField})
 * rather than being manually tracked as raw strings.
 */
public final class Constants {

    private Constants() {}

    public static String nodeType(GoNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(GoNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    // Query capture names (not node types / fields).
    public static final String TEST_MARKER = "test_marker";
    public static final String CAPTURE_TEST_CANDIDATE_NAME = "test_candidate.name";
    public static final String CAPTURE_TEST_CANDIDATE_PARAMS = "test_candidate.params";

    // Field names.
    public static final String IDENTIFIER_FIELD_NAME = nodeField(GoNodeField.NAME);
    public static final String BODY_FIELD_NAME = nodeField(GoNodeField.BODY);
    public static final String PARAMETERS_FIELD_NAME = nodeField(GoNodeField.PARAMETERS);
    public static final String RESULT_FIELD_NAME = nodeField(GoNodeField.RESULT);
    public static final String TYPE_PARAMETERS_FIELD_NAME = nodeField(GoNodeField.TYPE_PARAMETERS);
    public static final String RECEIVER_FIELD_NAME = nodeField(GoNodeField.RECEIVER);
    public static final String TYPE_FIELD_NAME = nodeField(GoNodeField.TYPE);
    public static final String TAG_FIELD_NAME = nodeField(GoNodeField.TAG);
    public static final String VALUE_FIELD_NAME = nodeField(GoNodeField.VALUE);

    // Common node type sets.
    public static final Set<String> COMMENT_NODE_TYPES = Set.of(nodeType(GoNodeType.COMMENT));

    public static final Set<String> CLASS_LIKE_NODE_TYPES =
            Set.of(nodeType(GoNodeType.TYPE_SPEC), nodeType(GoNodeType.TYPE_ALIAS));

    public static final Set<String> FUNCTION_LIKE_NODE_TYPES =
            Set.of(nodeType(GoNodeType.FUNCTION_DECLARATION), nodeType(GoNodeType.METHOD_DECLARATION));

    public static final Set<String> FIELD_LIKE_NODE_TYPES =
            Set.of(nodeType(GoNodeType.VAR_SPEC), nodeType(GoNodeType.CONST_SPEC));

    // Logging-related heuristics (not Tree-sitter).
    public static final Set<String> GO_LOG_RECEIVER_NAMES = Set.of("log", "logger", "zap", "slog", "fmt");
    public static final Set<String> GO_LOG_METHOD_NAMES =
            Set.of("print", "printf", "println", "debug", "info", "warn", "warning", "error", "fatal", "panic");

    // ===== Test assertion smell labels (shared string values) =====
    // These are semantic labels used in IAnalyzer.TestAssertionSmell.
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";

    public static final String TEST_FUNCTION_PREFIX = "Test";
    public static final String TESTING_T = "testing.T";
    public static final String POINTER_TESTING_T = "*testing.T";
}
