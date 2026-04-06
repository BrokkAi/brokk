package ai.brokk.analyzer.javascript;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for JavaScript TreeSitter node type names. */
public final class JavaScriptTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like declarations
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;

    // Function-like declarations
    public static final String FUNCTION_DECLARATION = CommonTreeSitterNodeTypes.FUNCTION_DECLARATION;
    public static final String ARROW_FUNCTION = CommonTreeSitterNodeTypes.ARROW_FUNCTION;
    public static final String METHOD_DEFINITION = CommonTreeSitterNodeTypes.METHOD_DEFINITION;

    // Variable declarations
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

    // ===== JAVASCRIPT-SPECIFIC TYPES =====
    // Class-like declarations
    public static final String CLASS_EXPRESSION = "class_expression";
    public static final String CLASS = "class";

    // Function-like declarations
    public static final String FUNCTION_EXPRESSION = "function_expression";

    public static final String IMPORT_DECLARATION = "import_declaration";

    // Capture name used in Tree-sitter queries for CommonJS require calls
    // These need to be filtered in Java code since #eq? predicate doesn't work in JNI Tree-sitter
    public static final String REQUIRE_CALL_CAPTURE_NAME = "module.require_call";
    public static final String REQUIRE_FUNC_CAPTURE_NAME = "_require_func";

    private JavaScriptTreeSitterNodeTypes() {}
}
