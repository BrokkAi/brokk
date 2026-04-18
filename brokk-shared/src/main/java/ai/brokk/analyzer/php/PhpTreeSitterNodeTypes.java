package ai.brokk.analyzer.php;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for PHP TreeSitter node type names. */
public final class PhpTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like declarations
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;
    public static final String INTERFACE_DECLARATION = CommonTreeSitterNodeTypes.INTERFACE_DECLARATION;

    // Method-like declarations
    public static final String METHOD_DECLARATION = CommonTreeSitterNodeTypes.METHOD_DECLARATION;

    // ===== PHP-SPECIFIC TYPES =====
    // Namespace definition
    public static final String NAMESPACE_DEFINITION = "namespace_definition";

    // Statements
    public static final String DECLARE_STATEMENT = "declare_statement";
    public static final String COMPOUND_STATEMENT = "compound_statement";
    public static final String TRY_STATEMENT = "try_statement";
    public static final String CATCH_CLAUSE = "catch_clause";
    public static final String THROW_STATEMENT = "throw_statement";
    public static final String EXPRESSION_STATEMENT = "expression_statement";
    public static final String VARIABLE_NAME = "variable_name";

    // Class-like declarations
    public static final String TRAIT_DECLARATION = "trait_declaration";

    // Function-like declarations
    public static final String FUNCTION_DEFINITION = "function_definition";

    // Field-like declarations
    public static final String PROPERTY_DECLARATION = "property_declaration";
    public static final String PROPERTY_ELEMENT = "property_element";
    public static final String PROPERTY_INITIALIZER = "property_initializer";
    public static final String CONST_DECLARATION = "const_declaration";
    public static final String CONST_ELEMENT = "const_element";

    // Modifiers and attributes
    public static final String ATTRIBUTE_LIST = "attribute_list";
    public static final String VISIBILITY_MODIFIER = "visibility_modifier";
    public static final String STATIC_MODIFIER = "static_modifier";
    public static final String ABSTRACT_MODIFIER = "abstract_modifier";
    public static final String FINAL_MODIFIER = "final_modifier";
    public static final String READONLY_MODIFIER = "readonly_modifier";
    public static final String REFERENCE_MODIFIER = "reference_modifier";

    // Keywords and tags
    public static final String PHP_TAG = "php_tag";
    public static final String FUNCTION_KEYWORD = "function";

    // Literals
    public static final String INTEGER = "integer";
    public static final String FLOAT = "float";
    public static final String STRING = "string";
    public static final String ENCAPSED_STRING = "encapsed_string";
    public static final String STRING_VALUE = "string_value";
    public static final String BOOLEAN = "boolean";
    public static final String BOOLEAN_LITERAL = "boolean_literal";
    public static final String NULL = "null";
    public static final String NULL_LITERAL = "null_literal";

    // Import declarations
    public static final String IMPORT_DECLARATION = "namespace_use_declaration";

    // Common leaf node types used by analyzers/queries
    public static final String NAME = "name";
    public static final String COMMENT = "comment";

    // Test markers
    public static final String TEST_MARKER = "test_marker";
    public static final String TEST_TAG_AT_TEST = "@test";

    private PhpTreeSitterNodeTypes() {}
}
