package ai.brokk.analyzer.javascript;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;
import java.util.Set;

/** Shared constants for JavaScript and TypeScript analyzer node types, field names, and semantic names. */
public final class Constants {

    private Constants() {}

    // Common node types.
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;
    public static final String INTERFACE_DECLARATION = CommonTreeSitterNodeTypes.INTERFACE_DECLARATION;
    public static final String ENUM_DECLARATION = CommonTreeSitterNodeTypes.ENUM_DECLARATION;
    public static final String FUNCTION_DECLARATION = CommonTreeSitterNodeTypes.FUNCTION_DECLARATION;
    public static final String METHOD_DEFINITION = CommonTreeSitterNodeTypes.METHOD_DEFINITION;
    public static final String ARROW_FUNCTION = CommonTreeSitterNodeTypes.ARROW_FUNCTION;
    public static final String CONSTRUCTOR_DECLARATION = CommonTreeSitterNodeTypes.CONSTRUCTOR_DECLARATION;
    public static final String VARIABLE_DECLARATOR = CommonTreeSitterNodeTypes.VARIABLE_DECLARATOR;
    public static final String LEXICAL_DECLARATION = CommonTreeSitterNodeTypes.LEXICAL_DECLARATION;
    public static final String VARIABLE_DECLARATION = CommonTreeSitterNodeTypes.VARIABLE_DECLARATION;
    public static final String EXPORT_STATEMENT = CommonTreeSitterNodeTypes.EXPORT_STATEMENT;
    public static final String DECORATOR = CommonTreeSitterNodeTypes.DECORATOR;

    // Statement and block node types.
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
    public static final String IMPORT_STATEMENT = "import_statement";

    // Expression and identifier node types.
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
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String NESTED_TYPE_IDENTIFIER = "nested_type_identifier";
    public static final String NULL = "null";
    public static final String UNDEFINED = "undefined";
    public static final String THIS = "this";
    public static final String OBJECT = "object";

    // JavaScript-specific node types.
    public static final String CLASS_EXPRESSION = "class_expression";
    public static final String CLASS = "class";
    public static final String FUNCTION_EXPRESSION = "function_expression";
    public static final String IMPORT_DECLARATION = "import_declaration";
    public static final String JSX_ELEMENT = "jsx_element";
    public static final String JSX_SELF_CLOSING_ELEMENT = "jsx_self_closing_element";
    public static final String JSX_FRAGMENT = "jsx_fragment";

    // TypeScript-specific node types.
    public static final String ABSTRACT_CLASS_DECLARATION = "abstract_class_declaration";
    public static final String MODULE = "module";
    public static final String INTERNAL_MODULE = "internal_module";
    public static final String GENERATOR_FUNCTION_DECLARATION = "generator_function_declaration";
    public static final String FUNCTION_SIGNATURE = "function_signature";
    public static final String METHOD_SIGNATURE = "method_signature";
    public static final String ABSTRACT_METHOD_SIGNATURE = "abstract_method_signature";
    public static final String CONSTRUCT_SIGNATURE = "construct_signature";
    public static final String CALL_SIGNATURE = "call_signature";
    public static final String PUBLIC_FIELD_DEFINITION = "public_field_definition";
    public static final String PROPERTY_SIGNATURE = "property_signature";
    public static final String INDEX_SIGNATURE = "index_signature";
    public static final String ENUM_MEMBER = "enum_member";
    public static final String AMBIENT_DECLARATION = "ambient_declaration";
    public static final String ENUM_BODY = "enum_body";
    public static final String ENUM_ASSIGNMENT = "enum_assignment";
    public static final String ACCESSIBILITY_MODIFIER = "accessibility_modifier";
    public static final String MODIFIERS = "modifiers";
    public static final String TYPE_PARAMETERS = "type_parameters";
    public static final String TYPE_ALIAS_DECLARATION = "type_alias_declaration";
    public static final String TYPE_ANNOTATION = "type_annotation";
    public static final String TYPE_ARGUMENTS = "type_arguments";
    public static final String TYPE_QUERY = "type_query";
    public static final String CLASS_BODY = "class_body";
    public static final String CLASS_HERITAGE = "class_heritage";
    public static final String EXTENDS_CLAUSE = "extends_clause";
    public static final String IMPLEMENTS_CLAUSE = "implements_clause";
    public static final String OBJECT_TYPE = "object_type";
    public static final String NEW_EXPRESSION = "new_expression";
    public static final String OBJECT_PATTERN = "object_pattern";
    public static final String ARRAY_PATTERN = "array_pattern";
    public static final String PAIR = "pair";
    public static final String PAIR_PATTERN = "pair_pattern";
    public static final String SHORTHAND_PROPERTY_IDENTIFIER_PATTERN = "shorthand_property_identifier_pattern";
    public static final String FORMAL_PARAMETERS = "formal_parameters";

    // Token node types and keyword-like node types.
    public static final String INTERFACE = "interface";
    public static final String ENUM = "enum";
    public static final String NAMESPACE = "namespace";
    public static final String ABSTRACT_CLASS = "abstract class";
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
    public static final String FIELD_NAME = "name";
    public static final String FIELD_BODY = "body";
    public static final String FIELD_PARAMETERS = "parameters";
    public static final String FIELD_RETURN_TYPE = "return_type";
    public static final String FIELD_TYPE_PARAMETERS = "type_parameters";
    public static final String FIELD_DECLARATION = "declaration";
    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_ARGUMENTS = "arguments";
    public static final String FIELD_OBJECT = "object";
    public static final String FIELD_PROPERTY = "property";
    public static final String FIELD_LEFT = "left";
    public static final String FIELD_RIGHT = "right";
    public static final String FIELD_VALUE = "value";
    public static final String FIELD_PARAMETER = "parameter";
    public static final String FIELD_OPERATOR = "operator";
    public static final String FIELD_PATTERN = "pattern";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_KEY = "key";
    public static final String FIELD_CONSTRUCTOR = "constructor";

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

    public static final Set<String> JS_TS_IDENTIFIER_TYPES = Set.of(IDENTIFIER, PROPERTY_IDENTIFIER);
    public static final Set<String> JS_TS_STRING_TYPES = Set.of(STRING, TEMPLATE_STRING);
    public static final Set<String> JS_TS_NUMBER_TYPES = Set.of(NUMBER);
    public static final Set<String> JS_TS_CLONE_AST_IGNORED_TYPES =
            Set.of(ACCESSIBILITY_MODIFIER, MODIFIERS, TYPE_PARAMETERS);
    public static final Set<String> TS_MODIFIER_NODE_TYPES = Set.of(
            EXPORT,
            DEFAULT,
            DECLARE,
            ABSTRACT,
            STATIC,
            READONLY,
            ACCESSIBILITY_MODIFIER,
            ASYNC,
            CONST,
            LET,
            VAR,
            OVERRIDE);
}
