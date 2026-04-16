package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;
import java.util.Set;

/** Constants for Java TreeSitter node type names. */
public final class JavaTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like declarations
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;
    public static final String INTERFACE_DECLARATION = CommonTreeSitterNodeTypes.INTERFACE_DECLARATION;
    public static final String ENUM_DECLARATION = CommonTreeSitterNodeTypes.ENUM_DECLARATION;

    // Method-like declarations
    public static final String METHOD_DECLARATION = CommonTreeSitterNodeTypes.METHOD_DECLARATION;
    public static final String CONSTRUCTOR_DECLARATION = CommonTreeSitterNodeTypes.CONSTRUCTOR_DECLARATION;

    // Field-like declarations
    public static final String FIELD_DECLARATION = CommonTreeSitterNodeTypes.FIELD_DECLARATION;

    // ===== JAVA-SPECIFIC TYPES =====
    // Class-like declarations
    public static final String RECORD_DECLARATION = "record_declaration";
    public static final String ANNOTATION_TYPE_DECLARATION = "annotation_type_declaration";

    // Field-like declarations
    public static final String ENUM_CONSTANT = "enum_constant";
    public static final String CONSTANT_DECLARATION = "constant_declaration";

    // Package declaration
    public static final String PACKAGE_DECLARATION = "package_declaration";

    // Import declarations
    public static final String IMPORT_DECLARATION = "import.declaration";

    // Test detection
    public static final String TEST_MARKER = "test_marker";

    // Annotations
    public static final String ANNOTATION = "annotation";
    public static final String MARKER_ANNOTATION = "marker_annotation";
    public static final String MODIFIERS = "modifiers";

    // Comments
    public static final String LINE_COMMENT = CommonTreeSitterNodeTypes.LINE_COMMENT;
    public static final String BLOCK_COMMENT = CommonTreeSitterNodeTypes.BLOCK_COMMENT;

    // Reference node types (used in isAccessExpression)
    public static final String ARGUMENT_LIST = "argument_list";
    public static final String METHOD_INVOCATION = "method_invocation";
    public static final String FIELD_ACCESS = "field_access";
    public static final String OBJECT_CREATION_EXPRESSION = "object_creation_expression";
    public static final String CLASS_BODY = "class_body";
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String SCOPED_TYPE_IDENTIFIER = "scoped_type_identifier";
    public static final String CLASS_LITERAL = "class_literal";
    public static final String IDENTIFIER = "identifier";
    public static final String SCOPED_IDENTIFIER = "scoped_identifier";

    // Declaration context node types
    public static final String VARIABLE_DECLARATOR = "variable_declarator";
    public static final String FORMAL_PARAMETER = "formal_parameter";
    public static final String SPREAD_PARAMETER = "spread_parameter";
    public static final String LOCAL_VARIABLE_DECLARATION = "local_variable_declaration";
    public static final String ENHANCED_FOR_STATEMENT = "enhanced_for_statement";
    public static final String CATCH_FORMAL_PARAMETER = "catch_formal_parameter";
    public static final String RESOURCE_SPECIFICATION = "resource_specification";
    public static final String RESOURCE = "resource";
    public static final String LAMBDA_EXPRESSION = "lambda_expression";
    public static final String INFERRED_PARAMETERS = "inferred_parameters";
    public static final String FORMAL_PARAMETERS = "formal_parameters";
    public static final String INSTANCEOF_EXPRESSION = "instanceof_expression";
    public static final String PATTERN = "pattern";

    // Control flow and complexity inducing types
    public static final String IF_STATEMENT = "if_statement";
    public static final String FOR_STATEMENT = "for_statement";
    public static final String WHILE_STATEMENT = "while_statement";
    public static final String DO_STATEMENT = "do_statement";
    public static final String CATCH_CLAUSE = "catch_clause";
    public static final String CONDITIONAL_EXPRESSION = "ternary_expression";
    public static final String SWITCH_LABEL = "switch_label";
    public static final String BINARY_EXPRESSION = "binary_expression";
    public static final String BLOCK = "block";
    public static final String EXPRESSION_STATEMENT = "expression_statement";
    public static final String THROW_STATEMENT = "throw_statement";
    public static final String RETURN_STATEMENT = "return_statement";
    public static final String BREAK_STATEMENT = "break_statement";
    public static final String CONTINUE_STATEMENT = "continue_statement";
    public static final String TRY_STATEMENT = "try_statement";
    public static final String TRY_WITH_RESOURCES_STATEMENT = "try_with_resources_statement";
    public static final String SWITCH_EXPRESSION = "switch_expression";

    // Literals
    public static final String DECIMAL_INTEGER_LITERAL = "decimal_integer_literal";
    public static final String HEX_INTEGER_LITERAL = "hex_integer_literal";
    public static final String OCTAL_INTEGER_LITERAL = "octal_integer_literal";
    public static final String BINARY_INTEGER_LITERAL = "binary_integer_literal";
    public static final String DECIMAL_FLOATING_POINT_LITERAL = "decimal_floating_point_literal";
    public static final String HEX_FLOATING_POINT_LITERAL = "hex_floating_point_literal";
    public static final String STRING_LITERAL = "string_literal";
    public static final String CHARACTER_LITERAL = "character_literal";
    public static final String BOOLEAN_LITERAL = "boolean_literal";
    public static final String NULL_LITERAL = "null_literal";
    public static final String TYPE_PARAMETERS = "type_parameters";

    // Keywords that can be literal values
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String NULL = "null";

    // Common Java testing method names
    public static final String ASSERT_THAT = "assertThat";
    public static final String ASSERT_TRUE = "assertTrue";
    public static final String ASSERT_FALSE = "assertFalse";
    public static final String ASSERT_EQUALS = "assertEquals";
    public static final String ASSERT_SAME = "assertSame";
    public static final String ASSERT_NOT_NULL = "assertNotNull";
    public static final String ASSERT_NULL = "assertNull";
    public static final String ASSERT_INSTANCE_OF = "assertInstanceOf";
    public static final String FAIL = "fail";
    public static final String EQUALS = "equals";
    public static final String IS_EQUAL_TO = "isEqualTo";
    public static final String IS_SAME_AS = "isSameAs";
    public static final String IS_TRUE = "isTrue";
    public static final String IS_FALSE = "isFalse";
    public static final String IS_NULL = "isNull";
    public static final String IS_NOT_NULL = "isNotNull";
    public static final String IS_INSTANCE_OF = "isInstanceOf";

    // Test assertion smell labels
    public static final String TEST_ASSERTION_KIND_JUNIT = "junit-assertion";
    public static final String TEST_ASSERTION_KIND_ASSERTJ = "assertj-assertion";
    public static final String TEST_ASSERTION_KIND_MOCK_VERIFICATION = "mock-verification";
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";
    public static final String TEST_ASSERTION_KIND_ANONYMOUS_TEST_DOUBLE = "anonymous-test-double";
    public static final String TEST_ASSERTION_REASON_REUSABLE_TEST_DOUBLE = "reusable-test-double-candidate";

    public static final Set<String> DECLARATION_TYPES = Set.of(
            METHOD_DECLARATION,
            FIELD_DECLARATION,
            CLASS_DECLARATION,
            INTERFACE_DECLARATION,
            ENUM_DECLARATION,
            RECORD_DECLARATION,
            VARIABLE_DECLARATOR,
            FORMAL_PARAMETER);
    public static final Set<String> ACCESS_TYPES = Set.of(
            METHOD_INVOCATION,
            FIELD_ACCESS,
            OBJECT_CREATION_EXPRESSION,
            TYPE_IDENTIFIER,
            SCOPED_TYPE_IDENTIFIER,
            MARKER_ANNOTATION,
            ANNOTATION,
            CLASS_LITERAL,
            IMPORT_DECLARATION);

    /**
     * Top-level statement node types in a catch body that indicate meaningful local handling work.
     */
    public static final Set<String> CATCH_BODY_MEANINGFUL_STATEMENT_TYPES = Set.of(
            EXPRESSION_STATEMENT,
            THROW_STATEMENT,
            RETURN_STATEMENT,
            BREAK_STATEMENT,
            CONTINUE_STATEMENT,
            IF_STATEMENT,
            FOR_STATEMENT,
            ENHANCED_FOR_STATEMENT,
            WHILE_STATEMENT,
            DO_STATEMENT,
            SWITCH_EXPRESSION,
            TRY_STATEMENT,
            TRY_WITH_RESOURCES_STATEMENT);

    public static final Set<String> JUNIT_ASSERTION_NAMES = Set.of(
            "assertArrayEquals",
            "assertDoesNotThrow",
            ASSERT_EQUALS,
            ASSERT_FALSE,
            ASSERT_INSTANCE_OF,
            "assertIterableEquals",
            "assertLinesMatch",
            "assertNotEquals",
            ASSERT_NOT_NULL,
            "assertNotSame",
            ASSERT_NULL,
            ASSERT_SAME,
            "assertThrows",
            "assertThrowsExactly",
            "assertTimeout",
            "assertTimeoutPreemptively",
            ASSERT_TRUE,
            FAIL);

    public static final Set<String> SHALLOW_ASSERTION_NAMES = Set.of(ASSERT_NOT_NULL, ASSERT_NULL, ASSERT_INSTANCE_OF);

    public static final Set<String> ASSERTJ_TERMINAL_NAMES = Set.of(
            IS_EQUAL_TO,
            IS_SAME_AS,
            "isNotEqualTo",
            IS_TRUE,
            IS_FALSE,
            IS_NULL,
            IS_NOT_NULL,
            IS_INSTANCE_OF,
            "hasMessage",
            "hasMessageContaining",
            "containsExactly",
            "containsExactlyInAnyOrder");

    public static final Set<String> ASSERTJ_SHALLOW_TERMINAL_NAMES = Set.of(IS_NULL, IS_NOT_NULL, IS_INSTANCE_OF);

    public static final Set<String> MOCKITO_VERIFY_NAMES =
            Set.of("verify", "verifyNoInteractions", "verifyNoMoreInteractions", "verifyZeroInteractions", "inOrder");

    public static final Set<String> CONSTANT_LITERAL_TYPES = Set.of(
            STRING_LITERAL,
            CHARACTER_LITERAL,
            DECIMAL_INTEGER_LITERAL,
            HEX_INTEGER_LITERAL,
            OCTAL_INTEGER_LITERAL,
            BINARY_INTEGER_LITERAL,
            DECIMAL_FLOATING_POINT_LITERAL,
            HEX_FLOATING_POINT_LITERAL,
            TRUE,
            FALSE,
            NULL,
            NULL_LITERAL);

    private JavaTreeSitterNodeTypes() {}
}
