package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

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

    // Reference node types (used in isDeclarationReference)
    public static final String METHOD_INVOCATION = "method_invocation";
    public static final String FIELD_ACCESS = "field_access";
    public static final String OBJECT_CREATION_EXPRESSION = "object_creation_expression";
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String SCOPED_TYPE_IDENTIFIER = "scoped_type_identifier";
    public static final String CLASS_LITERAL = "class_literal";

    // Declaration context node types
    public static final String VARIABLE_DECLARATOR = "variable_declarator";
    public static final String FORMAL_PARAMETER = "formal_parameter";

    private JavaTreeSitterNodeTypes() {}
}
