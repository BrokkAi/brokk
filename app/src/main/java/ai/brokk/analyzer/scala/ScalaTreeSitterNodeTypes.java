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

    // Test detection constants
    public static final String CALL_EXPRESSION = "call_expression";
    public static final String INFIX_EXPRESSION = "infix_expression";
    public static final String OPERATOR_IDENTIFIER = "operator_identifier";
    public static final String TEST_MARKER = "test_marker";
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String IMPORT_DECLARATION_NODE = "import_declaration";
    public static final String SCALATEST_IMPORT_SNIPPET = "org.scalatest";

    private ScalaTreeSitterNodeTypes() {}
}
