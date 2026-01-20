package ai.brokk.analyzer.python;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

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

    // Other common Python node types
    public static final String DECORATED_DEFINITION = "decorated_definition";
    public static final String ATTRIBUTE = "attribute";
    public static final String IDENTIFIER = "identifier";
    public static final String CALL = "call";

    // Common field names used by Tree-sitter Python grammar
    public static final String FIELD_NAME = "name";
    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_ATTRIBUTE = "attribute";
    public static final String FIELD_OBJECT = "object";

    // Pytest marker tokens/prefixes (used for semantic test detection)
    public static final String PYTEST = "pytest";
    public static final String MARK = "mark";
    public static final String PYTEST_MARK_PREFIX = "pytest.mark";

    // Import-related captures
    public static final String IMPORT_DECLARATION = "import.declaration";
    public static final String IMPORT_MODULE = "import.module";
    public static final String IMPORT_NAME = "import.name";
    public static final String IMPORT_ALIAS = "import.alias";
    public static final String IMPORT_RELATIVE = "import.relative";

    // Wildcard import captures
    public static final String IMPORT_WILDCARD = "import.wildcard";
    public static final String IMPORT_MODULE_WILDCARD = "import.module.wildcard";
    public static final String IMPORT_RELATIVE_WILDCARD = "import.relative.wildcard";

    // Test markers
    public static final String TEST_MARKER = "test_marker";

    private PythonTreeSitterNodeTypes() {}
}
