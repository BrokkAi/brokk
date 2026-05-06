package ai.brokk.analyzer.javascript;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import org.treesitter.TsxNodeField;
import org.treesitter.TsxNodeType;

/** Shared constants for JavaScript and TypeScript analyzer node types, field names, and semantic names. */
public final class Constants {

    private Constants() {}

    public static String nodeType(TsxNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(TsxNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    // Common node types.
    public static final String CONSTRUCTOR_DECLARATION = "constructor";

    // Statement and block node types.
    public static final String IF_STATEMENT = "if_statement";
    public static final String FOR_STATEMENT = "for_statement";
    public static final String FOR_IN_STATEMENT = "for_in_statement";
    public static final String WHILE_STATEMENT = "while_statement";
    public static final String DO_STATEMENT = "do_statement";
    public static final String CATCH_CLAUSE = "catch_clause";
    public static final String SWITCH_CASE = "switch_case";

    // Expression and identifier node types.
    public static final String TERNARY_EXPRESSION = "ternary_expression";
    public static final String BINARY_EXPRESSION = "binary_expression";

    // JavaScript-specific node types.
    public static final String CLASS_EXPRESSION = "class_expression";
    public static final String IMPORT_DECLARATION = "import_declaration";
    public static final String JSX_FRAGMENT = "jsx_fragment";

    // TypeScript-specific node types.
    public static final String ENUM_MEMBER = "enum_member";
    public static final String MODIFIERS = "modifiers";

    // Token node types and keyword-like node types.
    public static final String INTERFACE = "interface";
    public static final String ENUM = "enum";
    public static final String NAMESPACE = "namespace";
    public static final String ABSTRACT_CLASS = "abstract class";
    public static final String CLASS = "class";
    public static final String CONSTRUCTOR = "constructor";
    public static final String FUNCTION = "function";
    public static final String STATIC = "static";
    public static final String EXPORT = "export";
    public static final String DEFAULT = "default";
    public static final String DECLARE = "declare";
    public static final String ABSTRACT = "abstract";
    public static final String READONLY = "readonly";
    public static final String ASYNC = "async";
    public static final String CONST = "const";
    public static final String LET = "let";
    public static final String VAR = "var";
    public static final String OVERRIDE = "override";
    public static final String GET = "get";
    public static final String SET = "set";
    public static final String NEW = "new";

    // Field names.
    public static final String FIELD_NAME = nodeField(TsxNodeField.NAME);
    public static final String FIELD_BODY = nodeField(TsxNodeField.BODY);
    public static final String FIELD_PARAMETERS = nodeField(TsxNodeField.PARAMETERS);
    public static final String FIELD_RETURN_TYPE = nodeField(TsxNodeField.RETURN_TYPE);
    public static final String FIELD_TYPE_PARAMETERS = nodeField(TsxNodeField.TYPE_PARAMETERS);
    public static final String FIELD_DECLARATION = nodeField(TsxNodeField.DECLARATION);
    public static final String FIELD_FUNCTION = nodeField(TsxNodeField.FUNCTION);
    public static final String FIELD_ARGUMENTS = nodeField(TsxNodeField.ARGUMENTS);
    public static final String FIELD_OBJECT = nodeField(TsxNodeField.OBJECT);
    public static final String FIELD_PROPERTY = nodeField(TsxNodeField.PROPERTY);
    public static final String FIELD_LEFT = nodeField(TsxNodeField.LEFT);
    public static final String FIELD_RIGHT = nodeField(TsxNodeField.RIGHT);
    public static final String FIELD_VALUE = nodeField(TsxNodeField.VALUE);
    public static final String FIELD_PARAMETER = nodeField(TsxNodeField.PARAMETER);
    public static final String FIELD_OPERATOR = nodeField(TsxNodeField.OPERATOR);
    public static final String FIELD_PATTERN = nodeField(TsxNodeField.PATTERN);
    public static final String FIELD_TYPE = nodeField(TsxNodeField.TYPE);
    public static final String FIELD_KEY = nodeField(TsxNodeField.KEY);
    public static final String FIELD_CONSTRUCTOR = nodeField(TsxNodeField.CONSTRUCTOR);

    // Query capture names.
    public static final String IMPORT_DECLARATION_CAPTURE = "import.declaration";
    public static final String KEYWORD_MODIFIER_CAPTURE = "keyword.modifier";
    public static final String REQUIRE_CALL_CAPTURE_NAME = "module.require_call";
    public static final String REQUIRE_FUNC_CAPTURE_NAME = "_require_func";
    public static final String REQUIRE_FUNC_CAPTURE_NAME_FALLBACK = "require_func";

    // Test assertion smell labels.
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
    public static final String REQUIRE = "require";
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

    public static final Set<String> COMMENT_NODE_TYPES =
            Set.of(nodeType(TsxNodeType.COMMENT), nodeType(TsxNodeType.HTML_COMMENT), "line_comment", "block_comment");

    public static final Set<String> CATCH_BODY_MEANINGFUL_STATEMENT_TYPES = Set.of(
            nodeType(TsxNodeType.EXPRESSION_STATEMENT),
            nodeType(TsxNodeType.THROW_STATEMENT),
            nodeType(TsxNodeType.RETURN_STATEMENT),
            nodeType(TsxNodeType.BREAK_STATEMENT),
            nodeType(TsxNodeType.CONTINUE_STATEMENT),
            nodeType(TsxNodeType.IF_STATEMENT),
            nodeType(TsxNodeType.FOR_STATEMENT),
            nodeType(TsxNodeType.FOR_IN_STATEMENT),
            nodeType(TsxNodeType.WHILE_STATEMENT),
            nodeType(TsxNodeType.DO_STATEMENT),
            nodeType(TsxNodeType.SWITCH_STATEMENT),
            nodeType(TsxNodeType.TRY_STATEMENT),
            nodeType(TsxNodeType.TERNARY_EXPRESSION));

    public static final Set<String> CONSTANT_LITERAL_TYPES = Set.of(
            nodeType(TsxNodeType.STRING),
            nodeType(TsxNodeType.TEMPLATE_STRING),
            nodeType(TsxNodeType.NUMBER),
            nodeType(TsxNodeType.TRUE),
            nodeType(TsxNodeType.FALSE),
            nodeType(TsxNodeType.NULL),
            nodeType(TsxNodeType.UNDEFINED));

    public static final Set<String> JS_TS_IDENTIFIER_TYPES =
            Set.of(nodeType(TsxNodeType.IDENTIFIER), nodeType(TsxNodeType.PROPERTY_IDENTIFIER));
    public static final Set<String> JS_TS_STRING_TYPES =
            Set.of(nodeType(TsxNodeType.STRING), nodeType(TsxNodeType.TEMPLATE_STRING));
    public static final Set<String> JS_TS_NUMBER_TYPES = Set.of(nodeType(TsxNodeType.NUMBER));
    public static final Set<String> JS_TS_CLONE_AST_IGNORED_TYPES =
            Set.of(nodeType(TsxNodeType.ACCESSIBILITY_MODIFIER), MODIFIERS, nodeType(TsxNodeType.TYPE_PARAMETERS));
}
