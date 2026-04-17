package ai.brokk.analyzer.scala;

public class ScalaTreeSitterNodeTypes {

    // Package clause
    public static final String PACKAGE_CLAUSE = "package_clause";

    // Class-like declarations
    public static final String CLASS_DEFINITION = "class_definition";
    public static final String OBJECT_DEFINITION = "object_definition";
    public static final String INTERFACE_DEFINITION = "trait_definition";
    public static final String ENUM_DEFINITION = "enum_definition";

    // Function-like declarations
    public static final String FUNCTION_DEFINITION = "function_definition";

    // Field-like declarations
    public static final String VAL_DEFINITION = "val_definition";
    public static final String VAR_DEFINITION = "var_definition";
    public static final String SIMPLE_ENUM_CASE = "simple_enum_case";

    // Import declaration
    public static final String IMPORT_DECLARATION = "import.declaration";

    // Literals
    public static final String STRING = "string";
    public static final String INTERPOLATED_STRING = "interpolated_string";
    public static final String INTERPOLATED_VERBATIM_STRING = "interpolated_verbatim_string";
    public static final String BOOLEAN = "boolean";
    public static final String CHARACTER = "character";
    public static final String SYMBOL = "symbol";
    public static final String NULL = "null";

    // Test detection constants
    public static final String CALL_EXPRESSION = "call_expression";
    public static final String INFIX_EXPRESSION = "infix_expression";
    public static final String OPERATOR_IDENTIFIER = "operator_identifier";
    public static final String TEST_MARKER = "test_marker";
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String IMPORT_DECLARATION_NODE = "import_declaration";
    public static final String SCALATEST_IMPORT_SNIPPET = "org.scalatest";

    // Exception handling (Scala)
    public static final String TRY_EXPRESSION = "try_expression";
    public static final String CATCH_CLAUSE = "catch_clause";
    public static final String CASE_CLAUSE = "case_clause";
    public static final String CASE_BLOCK = "case_block";
    public static final String INDENTED_CASES = "indented_cases";
    public static final String BLOCK = "block";
    public static final String INDENTED_BLOCK = "indented_block";
    public static final String TYPED_PATTERN = "typed_pattern";
    public static final String GUARD = "guard";
    public static final String THROW_EXPRESSION = "throw_expression";
    public static final String UNIT = "unit";
    public static final String COMMENT = "comment";
    public static final String BLOCK_COMMENT = "block_comment";
    public static final String LINE_COMMENT = "line_comment";
    public static final String SPAN = "span";
    public static final String FIELD_EXPRESSION = "field_expression";
    public static final String BLOCK_EXPRESSION = "block_expression";
    public static final String IDENTIFIER = "identifier";
    public static final String ANNOTATION = "annotation";
    public static final String MARKER_ANNOTATION = "marker_annotation";
    public static final String ARGUMENTS = "arguments";
    public static final String ARGUMENT = "argument";
    public static final String ARGUMENT_LIST = "argument_list";
    public static final String ARGUMENTS_LIST = "arguments_list";

    // ===== Test assertion smell labels (shared string values) =====
    // These are semantic labels used in IAnalyzer.TestAssertionSmell.
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";

    private ScalaTreeSitterNodeTypes() {}
}
