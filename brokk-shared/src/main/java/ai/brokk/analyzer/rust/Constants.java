package ai.brokk.analyzer.rust;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import org.treesitter.RustNodeField;
import org.treesitter.RustNodeType;

public final class Constants {

    private Constants() {}

    public static String nodeType(RustNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(RustNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    // Query capture names (not node types / fields).
    public static final String IMPORT_DECLARATION_CAPTURE = "import.declaration";
    public static final String TEST_MARKER_CAPTURE = "test_marker";

    public static final Set<String> COMMENT_NODE_TYPES =
            Set.of(nodeType(RustNodeType.LINE_COMMENT), nodeType(RustNodeType.BLOCK_COMMENT));

    public static final Set<String> RUST_LOG_MACRO_NAMES = Set.of("trace", "debug", "info", "warn", "error");
    public static final Set<String> RUST_PRINT_MACRO_NAMES = Set.of("println", "eprintln");

    // Test assertion smell labels (shared string values)
    // These are semantic labels used in IAnalyzer.TestAssertionSmell.
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";
}
