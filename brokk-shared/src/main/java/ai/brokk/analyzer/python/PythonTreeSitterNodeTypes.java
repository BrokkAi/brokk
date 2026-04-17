package ai.brokk.analyzer.python;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;
import java.util.Set;

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
    public static final String EXPRESSION_STATEMENT = "expression_statement";
    public static final String IF_STATEMENT = "if_statement";
    public static final String ELIF_CLAUSE = "elif_clause";
    public static final String FOR_STATEMENT = "for_statement";
    public static final String WHILE_STATEMENT = "while_statement";
    public static final String EXCEPT_CLAUSE = "except_clause";
    public static final String TRY_STATEMENT = "try_statement";
    public static final String RAISE_STATEMENT = "raise_statement";
    public static final String ASSERT_STATEMENT = "assert_statement";
    public static final String RETURN_STATEMENT = "return_statement";
    public static final String BREAK_STATEMENT = "break_statement";
    public static final String CONTINUE_STATEMENT = "continue_statement";
    public static final String MATCH_STATEMENT = "match_statement";
    public static final String CASE_CLAUSE = "case_clause";
    public static final String CONDITIONAL_EXPRESSION = "conditional_expression";
    public static final String BOOLEAN_OPERATOR = "boolean_operator";
    public static final String COMPARISON_OPERATOR = "comparison_operator";
    public static final String NOT_OPERATOR = "not_operator";
    public static final String BLOCK = "block";
    public static final String MODULE = "module";

    // Literals and Keywords
    public static final String STRING = "string";
    public static final String STRING_CONTENT = "string_content";
    public static final String INTERPOLATION = "interpolation";
    public static final String INTEGER = "integer";
    public static final String FLOAT = "float";
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String BOOLEAN = "boolean";
    public static final String NONE = "none";

    // Other common Python node types
    public static final String DECORATED_DEFINITION = "decorated_definition";
    public static final String ATTRIBUTE = "attribute";
    public static final String IDENTIFIER = "identifier";
    public static final String KEYWORD_IDENTIFIER = "keyword_identifier";
    public static final String CALL = "call";
    public static final String ARGUMENT_LIST = "argument_list";
    public static final String COMMENT = CommonTreeSitterNodeTypes.COMMENT;
    public static final String LINE_COMMENT = CommonTreeSitterNodeTypes.LINE_COMMENT;
    public static final String BLOCK_COMMENT = CommonTreeSitterNodeTypes.BLOCK_COMMENT;

    // Common field names used by Tree-sitter Python grammar
    public static final String FIELD_NAME = "name";
    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_ARGUMENTS = "arguments";
    public static final String FIELD_ATTRIBUTE = "attribute";
    public static final String FIELD_OBJECT = "object";
    public static final String FIELD_LEFT = "left";
    public static final String FIELD_RIGHT = "right";
    public static final String FIELD_BODY = "body";

    // Pytest marker tokens/prefixes (used for semantic test detection)
    public static final String PYTEST = "pytest";
    public static final String MARK = "mark";
    public static final String FIXTURE = "fixture";
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

    public static final String ASSERT = "assert";
    public static final String PYTEST_RAISES = "raises";

    public static final String TEST_ASSERTION_KIND_UNITTEST = "unittest-assertion";
    public static final String TEST_ASSERTION_KIND_PY_ASSERT = "python-assert";
    public static final String TEST_ASSERTION_KIND_PYTEST_RAISES = "pytest-raises";
    public static final String TEST_ASSERTION_KIND_MOCK_VERIFICATION = "mock-verification";
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";

    public static final Set<String> COMMENT_NODE_TYPES = Set.of(COMMENT, LINE_COMMENT, BLOCK_COMMENT);

    public static final Set<String> UNITTEST_ASSERTION_NAMES = Set.of(
            "assertEqual",
            "assertNotEqual",
            "assertTrue",
            "assertFalse",
            "assertIs",
            "assertIsNot",
            "assertIsNone",
            "assertIsNotNone",
            "assertIn",
            "assertNotIn",
            "assertRaises");

    public static final Set<String> SHALLOW_ASSERTION_NAMES = Set.of("assertIsNone", "assertIsNotNone");

    public static final Set<String> MOCK_ASSERTION_NAMES = Set.of(
            "assert_called",
            "assert_called_once",
            "assert_called_with",
            "assert_called_once_with",
            "assert_any_call",
            "assert_has_calls",
            "assert_not_called");

    public static final Set<String> CONSTANT_LITERAL_TYPES = Set.of(STRING, INTEGER, FLOAT, TRUE, FALSE, BOOLEAN, NONE);

    public static final Set<String> CATCH_BODY_MEANINGFUL_STATEMENT_TYPES = Set.of(
            EXPRESSION_STATEMENT,
            RAISE_STATEMENT,
            RETURN_STATEMENT,
            BREAK_STATEMENT,
            CONTINUE_STATEMENT,
            IF_STATEMENT,
            FOR_STATEMENT,
            WHILE_STATEMENT,
            TRY_STATEMENT,
            MATCH_STATEMENT);

    private PythonTreeSitterNodeTypes() {}
}
