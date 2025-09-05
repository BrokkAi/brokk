package io.github.jbellis.brokk.analyzer.python;

import io.github.jbellis.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for Python TreeSitter node type names. */
public final class PythonTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like definitions
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;

    // Function-like definitions
    public static final String FUNCTION_DECLARATION = CommonTreeSitterNodeTypes.FUNCTION_DECLARATION;

    // Decorators
    public static final String DECORATOR = CommonTreeSitterNodeTypes.DECORATOR;

    // ===== PYTHON-SPECIFIC TYPES =====
    // Class-like definitions
    public static final String CLASS_DEFINITION = "class_definition";

    // Function-like definitions
    public static final String FUNCTION_DEFINITION = "function_definition";

    // Field-like definitions
    public static final String ASSIGNMENT = "assignment";
    public static final String TYPED_PARAMETER = "typed_parameter";

    // Statements
    public static final String PASS_STATEMENT = "pass_statement";

    // Other common Python node types that might be used
    public static final String DECORATED_DEFINITION = "decorated_definition";

    private PythonTreeSitterNodeTypes() {}
}
