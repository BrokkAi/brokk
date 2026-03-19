package ai.brokk.analyzer.csharp;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for C# TreeSitter node type names. */
public final class CSharpTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like declarations
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;
    public static final String INTERFACE_DECLARATION = CommonTreeSitterNodeTypes.INTERFACE_DECLARATION;

    // Method-like declarations
    public static final String METHOD_DECLARATION = CommonTreeSitterNodeTypes.METHOD_DECLARATION;
    public static final String CONSTRUCTOR_DECLARATION = CommonTreeSitterNodeTypes.CONSTRUCTOR_DECLARATION;

    // Field-like declarations
    public static final String FIELD_DECLARATION = CommonTreeSitterNodeTypes.FIELD_DECLARATION;
    // Variable-related nodes used when splitting declarators in multi-declaration fields
    public static final String VARIABLE_DECLARATION = "variable_declaration";
    public static final String VARIABLE_DECLARATOR = "variable_declarator";

    // ===== C#-SPECIFIC TYPES =====
    // Class-like declarations
    public static final String STRUCT_DECLARATION = "struct_declaration";
    public static final String RECORD_DECLARATION = "record_declaration";
    public static final String RECORD_STRUCT_DECLARATION = "record_struct_declaration";

    // Method-like declarations
    public static final String LOCAL_FUNCTION_STATEMENT = "local_function_statement";

    // Field-like declarations
    public static final String PROPERTY_DECLARATION = "property_declaration";
    public static final String EVENT_FIELD_DECLARATION = "event_field_declaration";

    // Literals
    public static final String BOOLEAN_LITERAL = "boolean_literal";
    public static final String INTEGER_LITERAL = "integer_literal";
    public static final String REAL_LITERAL = "real_literal";
    public static final String CHARACTER_LITERAL = "character_literal";
    public static final String STRING_LITERAL = "string_literal";
    public static final String NULL_LITERAL = "null_literal";
    public static final String TRUE_KEYWORD = "true";
    public static final String FALSE_KEYWORD = "false";
    public static final String NULL_KEYWORD = "null";

    // Namespace declaration
    public static final String NAMESPACE_DECLARATION = "namespace_declaration";

    public static final String IMPORT_DECLARATION = "using_directive";

    public static final String ATTRIBUTE_LIST = "attribute_list";

    public static final String TEST_MARKER = "test_marker";

    public static final String EQUALS_VALUE_CLAUSE = "equals_value_clause";
    public static final String PARENTHESIZED_EXPRESSION = "parenthesized_expression";
    public static final String LITERAL = "literal";

    private CSharpTreeSitterNodeTypes() {}
}
