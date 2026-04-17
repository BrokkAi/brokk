package ai.brokk.analyzer.typescript;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for TypeScript TreeSitter node type names. */
public final class TypeScriptTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like declarations
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;
    public static final String INTERFACE_DECLARATION = CommonTreeSitterNodeTypes.INTERFACE_DECLARATION;
    public static final String ENUM_DECLARATION = CommonTreeSitterNodeTypes.ENUM_DECLARATION;

    // Function-like declarations
    public static final String FUNCTION_DECLARATION = CommonTreeSitterNodeTypes.FUNCTION_DECLARATION;
    public static final String METHOD_DEFINITION = CommonTreeSitterNodeTypes.METHOD_DEFINITION;
    public static final String ARROW_FUNCTION = CommonTreeSitterNodeTypes.ARROW_FUNCTION;
    public static final String CONSTRUCTOR_DECLARATION = CommonTreeSitterNodeTypes.CONSTRUCTOR_DECLARATION;

    // Field-like declarations
    public static final String VARIABLE_DECLARATOR = CommonTreeSitterNodeTypes.VARIABLE_DECLARATOR;
    public static final String LEXICAL_DECLARATION = CommonTreeSitterNodeTypes.LEXICAL_DECLARATION;
    public static final String VARIABLE_DECLARATION = CommonTreeSitterNodeTypes.VARIABLE_DECLARATION;

    // Statements
    public static final String EXPORT_STATEMENT = CommonTreeSitterNodeTypes.EXPORT_STATEMENT;
    public static final String IF_STATEMENT = "if_statement";
    public static final String FOR_STATEMENT = "for_statement";
    public static final String FOR_IN_STATEMENT = "for_in_statement";
    public static final String WHILE_STATEMENT = "while_statement";
    public static final String DO_STATEMENT = "do_statement";
    public static final String CATCH_CLAUSE = "catch_clause";
    public static final String SWITCH_CASE = "switch_case";

    // Expressions
    public static final String TERNARY_EXPRESSION = "ternary_expression";
    public static final String BINARY_EXPRESSION = "binary_expression";
    public static final String IDENTIFIER = "identifier";
    public static final String STRING = "string";
    public static final String TEMPLATE_STRING = "template_string";
    public static final String NUMBER = "number";
    public static final String TRUE = "true";
    public static final String FALSE = "false";

    // Decorators
    public static final String DECORATOR = CommonTreeSitterNodeTypes.DECORATOR;

    // ===== TYPESCRIPT-SPECIFIC TYPES =====
    // Class-like declarations
    public static final String ABSTRACT_CLASS_DECLARATION = "abstract_class_declaration";
    public static final String MODULE = "module";
    public static final String INTERNAL_MODULE = "internal_module";

    // Function-like declarations
    public static final String GENERATOR_FUNCTION_DECLARATION = "generator_function_declaration";
    public static final String FUNCTION_SIGNATURE = "function_signature";
    public static final String METHOD_SIGNATURE = "method_signature";
    public static final String ABSTRACT_METHOD_SIGNATURE = "abstract_method_signature";
    public static final String CONSTRUCT_SIGNATURE = "construct_signature";

    // Field-like declarations
    public static final String PUBLIC_FIELD_DEFINITION = "public_field_definition";
    public static final String PROPERTY_SIGNATURE = "property_signature";
    public static final String ENUM_MEMBER = "enum_member";

    // Statements and blocks
    public static final String AMBIENT_DECLARATION = "ambient_declaration";
    public static final String STATEMENT_BLOCK = "statement_block";
    public static final String ENUM_BODY = "enum_body";

    // Enum types
    public static final String PROPERTY_IDENTIFIER = "property_identifier";
    public static final String ENUM_ASSIGNMENT = "enum_assignment";

    // Modifier types
    public static final String ACCESSIBILITY_MODIFIER = "accessibility_modifier";
    public static final String MODIFIERS = "modifiers";
    public static final String TYPE_PARAMETERS = "type_parameters";

    // Other types
    public static final String INTERFACE = "interface";
    public static final String ENUM = "enum";
    public static final String NAMESPACE = "namespace";
    public static final String ABSTRACT_CLASS = "abstract class";
    public static final String IMPORT_DECLARATION = "import_declaration";

    private TypeScriptTreeSitterNodeTypes() {}
}
