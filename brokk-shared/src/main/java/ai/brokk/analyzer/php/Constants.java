package ai.brokk.analyzer.php;

import static java.util.Objects.requireNonNull;

import org.treesitter.PhpNodeField;
import org.treesitter.PhpNodeType;

/** Tree-sitter-related constants for PHP analysis (node types, field names, query capture names). */
public final class Constants {

    public static String nodeType(PhpNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(PhpNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    public static final String FLOAT = "float";
    public static final String THROW_STATEMENT = "throw_statement";

    public static final String FIELD_ARGUMENTS = nodeField(PhpNodeField.ARGUMENTS);
    public static final String FIELD_BODY = nodeField(PhpNodeField.BODY);
    public static final String FIELD_DEFAULT_VALUE = nodeField(PhpNodeField.DEFAULT_VALUE);
    public static final String FIELD_FUNCTION = nodeField(PhpNodeField.FUNCTION);
    public static final String FIELD_LEFT = nodeField(PhpNodeField.LEFT);
    public static final String FIELD_NAME = nodeField(PhpNodeField.NAME);
    public static final String FIELD_RIGHT = nodeField(PhpNodeField.RIGHT);
    public static final String FIELD_RETURN_TYPE = nodeField(PhpNodeField.RETURN_TYPE);
    public static final String FIELD_TYPE = nodeField(PhpNodeField.TYPE);
    public static final String FIELD_VALUE = nodeField(PhpNodeField.VALUE);

    public static final String ARGUMENT = "argument";
    public static final String BOOLEAN_LITERAL = "boolean_literal";
    public static final String CALL_EXPRESSION = "call_expression";
    public static final String ENCAPSED_STRING = "encapsed_string";
    public static final String FIELD_MEMBER = "member";
    public static final String FIELD_PARAMETERS = "parameters";
    public static final String FIELD_PROPERTY = "property";
    public static final String FUNCTION_KEYWORD = "function";
    public static final String IMPORT_DECLARATION = "namespace_use_declaration";
    public static final String NULL_LITERAL = "null_literal";
    public static final String PROPERTY_INITIALIZER = "property_initializer";

    // ===== QUERY CAPTURE NAMES =====
    public static final String CAPTURE_NSNAME = "nsname";
    public static final String CAPTURE_NAMESPACE_NAME = "namespace.name";

    // Common leaf node types used by analyzers/queries
    // Test markers
    public static final String TEST_MARKER = "test_marker";
    public static final String TEST_TAG_AT_TEST = "@test";

    private Constants() {}
}
