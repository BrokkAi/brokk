package ai.brokk.analyzer.java;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import org.treesitter.JavaNodeField;
import org.treesitter.JavaNodeType;

public final class Constants {

    private Constants() {}

    public static String nodeType(JavaNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String nodeField(JavaNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    // Query capture names (not node types / fields).
    public static final String IMPORT_DECLARATION_CAPTURE = "import.declaration";
    public static final String TEST_MARKER_CAPTURE = "test_marker";

    public static final String NULL_KEYWORD = "null";

    // Common Java testing method names
    public static final String ASSERT_THAT = "assertThat";
    public static final String ASSERT_ARRAY_EQUALS = "assertArrayEquals";
    public static final String ASSERT_DOES_NOT_THROW = "assertDoesNotThrow";
    public static final String ASSERT_TRUE = "assertTrue";
    public static final String ASSERT_FALSE = "assertFalse";
    public static final String ASSERT_EQUALS = "assertEquals";
    public static final String ASSERT_ITERABLE_EQUALS = "assertIterableEquals";
    public static final String ASSERT_LINES_MATCH = "assertLinesMatch";
    public static final String ASSERT_NOT_EQUALS = "assertNotEquals";
    public static final String ASSERT_SAME = "assertSame";
    public static final String ASSERT_NOT_NULL = "assertNotNull";
    public static final String ASSERT_NOT_SAME = "assertNotSame";
    public static final String ASSERT_NULL = "assertNull";
    public static final String ASSERT_INSTANCE_OF = "assertInstanceOf";
    public static final String ASSERT_THROWS = "assertThrows";
    public static final String ASSERT_THROWS_EXACTLY = "assertThrowsExactly";
    public static final String ASSERT_TIMEOUT = "assertTimeout";
    public static final String ASSERT_TIMEOUT_PREEMPTIVELY = "assertTimeoutPreemptively";
    public static final String FAIL = "fail";
    public static final String EQUALS = "equals";
    public static final String IS_EQUAL_TO = "isEqualTo";
    public static final String IS_SAME_AS = "isSameAs";
    public static final String IS_NOT_EQUAL_TO = "isNotEqualTo";
    public static final String IS_TRUE = "isTrue";
    public static final String IS_FALSE = "isFalse";
    public static final String IS_NULL = "isNull";
    public static final String IS_NOT_NULL = "isNotNull";
    public static final String IS_INSTANCE_OF = "isInstanceOf";
    public static final String HAS_MESSAGE = "hasMessage";
    public static final String HAS_MESSAGE_CONTAINING = "hasMessageContaining";
    public static final String CONTAINS_EXACTLY = "containsExactly";
    public static final String CONTAINS_EXACTLY_IN_ANY_ORDER = "containsExactlyInAnyOrder";

    public static final String VERIFY = "verify";
    public static final String VERIFY_NO_INTERACTIONS = "verifyNoInteractions";
    public static final String VERIFY_NO_MORE_INTERACTIONS = "verifyNoMoreInteractions";
    public static final String VERIFY_ZERO_INTERACTIONS = "verifyZeroInteractions";
    public static final String IN_ORDER = "inOrder";

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
            nodeType(JavaNodeType.METHOD_DECLARATION),
            nodeType(JavaNodeType.FIELD_DECLARATION),
            nodeType(JavaNodeType.CLASS_DECLARATION),
            nodeType(JavaNodeType.INTERFACE_DECLARATION),
            nodeType(JavaNodeType.ENUM_DECLARATION),
            nodeType(JavaNodeType.RECORD_DECLARATION),
            nodeType(JavaNodeType.VARIABLE_DECLARATOR),
            nodeType(JavaNodeType.FORMAL_PARAMETER));

    public static final Set<String> ACCESS_TYPES = Set.of(
            nodeType(JavaNodeType.METHOD_INVOCATION),
            nodeType(JavaNodeType.FIELD_ACCESS),
            nodeType(JavaNodeType.OBJECT_CREATION_EXPRESSION),
            nodeType(JavaNodeType.TYPE_IDENTIFIER),
            nodeType(JavaNodeType.SCOPED_TYPE_IDENTIFIER),
            nodeType(JavaNodeType.MARKER_ANNOTATION),
            nodeType(JavaNodeType.ANNOTATION),
            nodeType(JavaNodeType.CLASS_LITERAL),
            IMPORT_DECLARATION_CAPTURE);

    /**
     * Top-level statement node types in a catch body that indicate meaningful local handling work.
     */
    public static final Set<String> CATCH_BODY_MEANINGFUL_STATEMENT_TYPES = Set.of(
            nodeType(JavaNodeType.EXPRESSION_STATEMENT),
            nodeType(JavaNodeType.THROW_STATEMENT),
            nodeType(JavaNodeType.RETURN_STATEMENT),
            nodeType(JavaNodeType.BREAK_STATEMENT),
            nodeType(JavaNodeType.CONTINUE_STATEMENT),
            nodeType(JavaNodeType.IF_STATEMENT),
            nodeType(JavaNodeType.FOR_STATEMENT),
            nodeType(JavaNodeType.ENHANCED_FOR_STATEMENT),
            nodeType(JavaNodeType.WHILE_STATEMENT),
            nodeType(JavaNodeType.DO_STATEMENT),
            nodeType(JavaNodeType.SWITCH_EXPRESSION),
            nodeType(JavaNodeType.TRY_STATEMENT),
            nodeType(JavaNodeType.TRY_WITH_RESOURCES_STATEMENT));

    public static final Set<String> JUNIT_ASSERTION_NAMES = Set.of(
            ASSERT_ARRAY_EQUALS,
            ASSERT_DOES_NOT_THROW,
            ASSERT_EQUALS,
            ASSERT_FALSE,
            ASSERT_INSTANCE_OF,
            ASSERT_ITERABLE_EQUALS,
            ASSERT_LINES_MATCH,
            ASSERT_NOT_EQUALS,
            ASSERT_NOT_NULL,
            ASSERT_NOT_SAME,
            ASSERT_NULL,
            ASSERT_SAME,
            ASSERT_THROWS,
            ASSERT_THROWS_EXACTLY,
            ASSERT_TIMEOUT,
            ASSERT_TIMEOUT_PREEMPTIVELY,
            ASSERT_TRUE,
            FAIL);

    public static final Set<String> SHALLOW_ASSERTION_NAMES = Set.of(ASSERT_NOT_NULL, ASSERT_NULL, ASSERT_INSTANCE_OF);

    public static final Set<String> ASSERTJ_TERMINAL_NAMES = Set.of(
            IS_EQUAL_TO,
            IS_SAME_AS,
            IS_NOT_EQUAL_TO,
            IS_TRUE,
            IS_FALSE,
            IS_NULL,
            IS_NOT_NULL,
            IS_INSTANCE_OF,
            HAS_MESSAGE,
            HAS_MESSAGE_CONTAINING,
            CONTAINS_EXACTLY,
            CONTAINS_EXACTLY_IN_ANY_ORDER);

    public static final Set<String> ASSERTJ_SHALLOW_TERMINAL_NAMES = Set.of(IS_NULL, IS_NOT_NULL, IS_INSTANCE_OF);

    public static final Set<String> MOCKITO_VERIFY_NAMES =
            Set.of(VERIFY, VERIFY_NO_INTERACTIONS, VERIFY_NO_MORE_INTERACTIONS, VERIFY_ZERO_INTERACTIONS, IN_ORDER);

    public static final Set<String> CONSTANT_LITERAL_TYPES = Set.of(
            nodeType(JavaNodeType.STRING_LITERAL),
            nodeType(JavaNodeType.CHARACTER_LITERAL),
            nodeType(JavaNodeType.DECIMAL_INTEGER_LITERAL),
            nodeType(JavaNodeType.HEX_INTEGER_LITERAL),
            nodeType(JavaNodeType.OCTAL_INTEGER_LITERAL),
            nodeType(JavaNodeType.BINARY_INTEGER_LITERAL),
            nodeType(JavaNodeType.DECIMAL_FLOATING_POINT_LITERAL),
            nodeType(JavaNodeType.HEX_FLOATING_POINT_LITERAL),
            nodeType(JavaNodeType.TRUE),
            nodeType(JavaNodeType.FALSE),
            NULL_KEYWORD,
            nodeType(JavaNodeType.NULL_LITERAL));
}
