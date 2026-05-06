package ai.brokk.analyzer.csharp;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import org.treesitter.CSharpNodeField;
import org.treesitter.CSharpNodeType;

public final class Constants {

    private Constants() {}

    public static String nodeType(CSharpNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(CSharpNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    public static final Set<String> TEST_METHOD_ATTRIBUTES =
            Set.of("fact", "theory", "test", "testcase", "testmethod", "datatestmethod");

    public static final Set<String> CSHARP_ASSERTION_METHOD_NAMES = Set.of(
            "equal",
            "equals",
            "same",
            "notsame",
            "true",
            "false",
            "null",
            "notnull",
            "istrue",
            "isfalse",
            "areequal",
            "arenotequal");

    public static final Set<String> CSHARP_SHALLOW_ASSERTION_METHOD_NAMES = Set.of("null", "notnull");

    public static final String TEST_ASSERTION_KIND_CSHARP = "csharp-assertion";
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";

    public static final String EQUALS_VALUE_CLAUSE = "equals_value_clause";
    public static final String FIELD_ARGUMENTS = nodeField(CSharpNodeField.ARGUMENTS);
    public static final String FIELD_BODY = nodeField(CSharpNodeField.BODY);
    public static final String FIELD_EXPRESSION = nodeField(CSharpNodeField.EXPRESSION);
    public static final String FIELD_FUNCTION = nodeField(CSharpNodeField.FUNCTION);
    public static final String FIELD_NAME = nodeField(CSharpNodeField.NAME);
    public static final String FIELD_PARAMETERS = nodeField(CSharpNodeField.PARAMETERS);
    public static final String FIELD_TYPE = nodeField(CSharpNodeField.TYPE);
    public static final String FIELD_VALUE = nodeField(CSharpNodeField.VALUE);

    public static final String BLOCK_COMMENT = "block_comment";
    public static final String FALSE_KEYWORD = "false";
    public static final String IMPORT_DECLARATION = "using_directive";
    public static final String LINE_COMMENT = "line_comment";
    public static final String NULL_KEYWORD = "null";
    public static final String RECORD_STRUCT_DECLARATION = "record_struct_declaration";
    public static final String TEST_MARKER = "test_marker";
    public static final String TRUE_KEYWORD = "true";
}
