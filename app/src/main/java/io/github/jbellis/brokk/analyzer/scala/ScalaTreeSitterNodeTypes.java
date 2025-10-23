package io.github.jbellis.brokk.analyzer.scala;

public class ScalaTreeSitterNodeTypes {

    // Package clause
    public static final String PACKAGE_CLAUSE = "package_clause";

    // Class-like declarations
    public static final String CLASS_DEFINITION = "class_definition";
    public static final String OBJECT_DEFINITION = "object_definition";
    public static final String INTERFACE_DEFINITION = "trait_definition";
    public static final String ENUM_DEFINITION = "enum_definition";

    // Function-like declarations

    // Import declaration
    public static final String IMPORT_DECLARATION = "import_declaration";

    private ScalaTreeSitterNodeTypes() {}
}
