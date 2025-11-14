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

    private ScalaTreeSitterNodeTypes() {}
}
