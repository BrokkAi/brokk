package ai.brokk.analyzer.python;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;
import java.util.Set;
import java.util.regex.Pattern;
import org.treesitter.PythonNodeField;
import org.treesitter.PythonNodeType;

public final class Constants {

    private Constants() {}

    public static String nodeType(PythonNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(PythonNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    // Query capture names (not node types / fields).
    public static final String IMPORT_DECLARATION_CAPTURE = "import.declaration";
    public static final String IMPORT_MODULE_CAPTURE = "import.module";
    public static final String IMPORT_NAME_CAPTURE = "import.name";
    public static final String IMPORT_ALIAS_CAPTURE = "import.alias";
    public static final String IMPORT_RELATIVE_CAPTURE = "import.relative";

    public static final String IMPORT_WILDCARD_CAPTURE = "import.wildcard";
    public static final String IMPORT_MODULE_WILDCARD_CAPTURE = "import.module.wildcard";
    public static final String IMPORT_RELATIVE_WILDCARD_CAPTURE = "import.relative.wildcard";

    public static final String TEST_MARKER_CAPTURE = "test_marker";

    // Pytest marker tokens/prefixes (used for semantic test detection).
    public static final String PYTEST = "pytest";
    public static final String MARK = "mark";
    public static final String FIXTURE = "fixture";
    public static final String PYTEST_MARK_PREFIX = "pytest.mark";

    public static final String ASSERT = "assert";
    public static final String PYTEST_RAISES = "raises";

    public static final Pattern WILDCARD_IMPORT_PATTERN = Pattern.compile("^from\\s+(.+?)\\s+import\\s+\\*");

    public static final Set<String> PYTHON_LOG_BARE_NAMES = Set.of("log", "logger", "warning", "error", "exception");
    public static final Set<String> PYTHON_LOG_RECEIVER_NAMES = Set.of("log", "logger");
    public static final Set<String> PYTHON_LOG_RECEIVER_EXTRA_SUFFIXES = Set.of("logging");
    public static final Set<String> PYTHON_LOG_METHOD_NAMES = Set.of("log", "warning", "error", "exception");

    // Test assertion smell labels.
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

    // Comment node types are common across languages; Python's grammar uses "comment".
    public static final Set<String> COMMENT_NODE_TYPES = Set.of(
            CommonTreeSitterNodeTypes.COMMENT,
            CommonTreeSitterNodeTypes.LINE_COMMENT,
            CommonTreeSitterNodeTypes.BLOCK_COMMENT);

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

    public static final Set<String> CLONE_AST_IDENTIFIER_TYPES = Set.of(nodeType(PythonNodeType.IDENTIFIER));

    public static final Set<String> CLONE_AST_STRING_TYPES = Set.of(
            nodeType(PythonNodeType.STRING),
            nodeType(PythonNodeType.STRING_CONTENT),
            nodeType(PythonNodeType.INTERPOLATION));

    public static final Set<String> CLONE_AST_NUMBER_TYPES =
            Set.of(nodeType(PythonNodeType.INTEGER), nodeType(PythonNodeType.FLOAT_));

    public static final Set<String> COMPLEXITY_NODE_TYPES = Set.of(
            nodeType(PythonNodeType.IF_STATEMENT),
            nodeType(PythonNodeType.ELIF_CLAUSE),
            nodeType(PythonNodeType.FOR_STATEMENT),
            nodeType(PythonNodeType.WHILE_STATEMENT),
            nodeType(PythonNodeType.EXCEPT_CLAUSE),
            nodeType(PythonNodeType.CONDITIONAL_EXPRESSION),
            nodeType(PythonNodeType.CASE_CLAUSE));

    public static final Set<String> CONSTANT_LITERAL_TYPES = Set.of(
            nodeType(PythonNodeType.STRING),
            nodeType(PythonNodeType.INTEGER),
            nodeType(PythonNodeType.FLOAT_),
            nodeType(PythonNodeType.TRUE),
            nodeType(PythonNodeType.FALSE),
            nodeType(PythonNodeType.NONE));

    public static final Set<String> CATCH_BODY_MEANINGFUL_STATEMENT_TYPES = Set.of(
            nodeType(PythonNodeType.EXPRESSION_STATEMENT),
            nodeType(PythonNodeType.RAISE_STATEMENT),
            nodeType(PythonNodeType.RETURN_STATEMENT),
            nodeType(PythonNodeType.BREAK_STATEMENT),
            nodeType(PythonNodeType.CONTINUE_STATEMENT),
            nodeType(PythonNodeType.IF_STATEMENT),
            nodeType(PythonNodeType.FOR_STATEMENT),
            nodeType(PythonNodeType.WHILE_STATEMENT),
            nodeType(PythonNodeType.TRY_STATEMENT),
            nodeType(PythonNodeType.MATCH_STATEMENT));
}
