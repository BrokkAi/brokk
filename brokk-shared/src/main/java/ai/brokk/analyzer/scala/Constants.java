package ai.brokk.analyzer.scala;

import static java.util.Objects.requireNonNull;

import org.treesitter.ScalaNodeField;
import org.treesitter.ScalaNodeType;

public final class Constants {
    private Constants() {}

    public static String nodeType(ScalaNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(ScalaNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    public static final String MARKER_ANNOTATION = "marker_annotation";
    public static final String SPAN = "span";
    public static final String TYPE_IDENTIFIER = "type_identifier";

    public static final String ARGUMENT_LIST = "argument_list";
    public static final String ARGUMENTS_LIST = "arguments_list";
    public static final String BLOCK_EXPRESSION = "block_expression";
    public static final String BOOLEAN = "boolean";
    public static final String IMPORT_DECLARATION = "import.declaration";
    public static final String INTERPOLATED_VERBATIM_STRING = "interpolated_verbatim_string";
    public static final String LINE_COMMENT = "line_comment";
    public static final String SCALATEST_IMPORT_SNIPPET = "org.scalatest";
    public static final String SYMBOL = "symbol";
    public static final String TEST_MARKER = "test_marker";

    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";
}
