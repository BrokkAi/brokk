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

    // ===== JAVASCRIPT-SPECIFIC TYPES =====
    // Class-like declarations
    public static final String CLASS_EXPRESSION = "class_expression";
    public static final String CLASS = "class";

    // Function-like declarations
    public static final String FUNCTION_EXPRESSION = "function_expression";

    public static final String IMPORT_DECLARATION = "module.import_statement";

    private JavaScriptTreeSitterNodeTypes() {}
}
