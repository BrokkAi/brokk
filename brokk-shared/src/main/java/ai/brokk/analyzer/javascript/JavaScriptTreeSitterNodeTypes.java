package ai.brokk.analyzer.javascript;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;
import java.util.Set;

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
    public static final String SWITCH_STATEMENT = "switch_statement";
    public static final String TRY_STATEMENT = "try_statement";
    public static final String THROW_STATEMENT = "throw_statement";
    public static final String RETURN_STATEMENT = "return_statement";
    public static final String BREAK_STATEMENT = "break_statement";
    public static final String CONTINUE_STATEMENT = "continue_statement";
    public static final String EXPRESSION_STATEMENT = "expression_statement";
    public static final String STATEMENT_BLOCK = "statement_block";

    // Expressions
    public static final String TERNARY_EXPRESSION = "ternary_expression";
    public static final String BINARY_EXPRESSION = "binary_expression";
    public static final String CALL_EXPRESSION = "call_expression";
    public static final String MEMBER_EXPRESSION = "member_expression";
    public static final String ARGUMENTS = "arguments";
    public static final String IDENTIFIER = "identifier";
    public static final String STRING = "string";
    public static final String TEMPLATE_STRING = "template_string";
    public static final String NUMBER = "number";
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String PROPERTY_IDENTIFIER = "property_identifier";
    public static final String NULL = "null";
    public static final String UNDEFINED = "undefined";

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

    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_ARGUMENTS = "arguments";
    public static final String FIELD_OBJECT = "object";
    public static final String FIELD_PROPERTY = "property";
    public static final String FIELD_LEFT = "left";
    public static final String FIELD_RIGHT = "right";
    public static final String FIELD_BODY = "body";

    public static final String TEST_ASSERTION_KIND_EXPECT = "expect-assertion";
    public static final String TEST_ASSERTION_KIND_ASSERT = "assert-assertion";
    public static final String TEST_ASSERTION_KIND_MOCK_VERIFICATION = "mock-verification";
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";
    public static final String TEST_ASSERTION_KIND_SNAPSHOT = "snapshot-assertion";

    public static final String TEST_FN_TEST = "test";
    public static final String TEST_FN_IT = "it";
    public static final String TEST_FN_DESCRIBE = "describe";
    public static final String EXPECT = "expect";
    public static final String ASSERT = "assert";
    public static final String TO_BE = "toBe";
    public static final String TO_EQUAL = "toEqual";
    public static final String TO_STRICT_EQUAL = "toStrictEqual";
    public static final String TO_BE_TRUTHY = "toBeTruthy";
    public static final String TO_BE_FALSY = "toBeFalsy";
    public static final String TO_BE_NULL = "toBeNull";
    public static final String TO_BE_UNDEFINED = "toBeUndefined";
    public static final String TO_MATCH_SNAPSHOT = "toMatchSnapshot";
    public static final String TO_MATCH_INLINE_SNAPSHOT = "toMatchInlineSnapshot";
    public static final String TO_CONTAIN = "toContain";
    public static final String TO_HAVE_LENGTH = "toHaveLength";
    public static final String TO_THROW = "toThrow";
    public static final String TO_HAVE_BEEN_CALLED = "toHaveBeenCalled";
    public static final String TO_HAVE_BEEN_CALLED_TIMES = "toHaveBeenCalledTimes";
    public static final String TO_HAVE_BEEN_CALLED_WITH = "toHaveBeenCalledWith";

    public static final Set<String> COMMENT_NODE_TYPES = Set.of(
            CommonTreeSitterNodeTypes.COMMENT,
            CommonTreeSitterNodeTypes.LINE_COMMENT,
            CommonTreeSitterNodeTypes.BLOCK_COMMENT);

    public static final Set<String> CATCH_BODY_MEANINGFUL_STATEMENT_TYPES = Set.of(
            EXPRESSION_STATEMENT,
            THROW_STATEMENT,
            RETURN_STATEMENT,
            BREAK_STATEMENT,
            CONTINUE_STATEMENT,
            IF_STATEMENT,
            FOR_STATEMENT,
            FOR_IN_STATEMENT,
            WHILE_STATEMENT,
            DO_STATEMENT,
            SWITCH_STATEMENT,
            TRY_STATEMENT,
            TERNARY_EXPRESSION);

    public static final Set<String> TEST_FUNCTION_NAMES = Set.of(TEST_FN_TEST, TEST_FN_IT, TEST_FN_DESCRIBE);

    public static final Set<String> EXPECT_TERMINAL_NAMES = Set.of(
            TO_BE,
            TO_EQUAL,
            TO_STRICT_EQUAL,
            TO_BE_TRUTHY,
            TO_BE_FALSY,
            TO_BE_NULL,
            TO_BE_UNDEFINED,
            TO_CONTAIN,
            TO_HAVE_LENGTH,
            TO_THROW,
            TO_MATCH_SNAPSHOT,
            TO_MATCH_INLINE_SNAPSHOT,
            TO_HAVE_BEEN_CALLED,
            TO_HAVE_BEEN_CALLED_TIMES,
            TO_HAVE_BEEN_CALLED_WITH);

    public static final Set<String> SHALLOW_EXPECT_TERMINAL_NAMES =
            Set.of(TO_BE_TRUTHY, TO_BE_FALSY, TO_BE_NULL, TO_BE_UNDEFINED);

    public static final Set<String> SNAPSHOT_EXPECT_TERMINAL_NAMES =
            Set.of(TO_MATCH_SNAPSHOT, TO_MATCH_INLINE_SNAPSHOT);

    public static final Set<String> MOCK_VERIFY_TERMINAL_NAMES =
            Set.of(TO_HAVE_BEEN_CALLED, TO_HAVE_BEEN_CALLED_TIMES, TO_HAVE_BEEN_CALLED_WITH);

    public static final Set<String> CONSTANT_LITERAL_TYPES =
            Set.of(STRING, TEMPLATE_STRING, NUMBER, TRUE, FALSE, NULL, UNDEFINED);

    private JavaScriptTreeSitterNodeTypes() {}
}
