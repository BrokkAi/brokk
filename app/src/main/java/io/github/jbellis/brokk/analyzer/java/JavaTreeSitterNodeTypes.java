package io.github.jbellis.brokk.analyzer.java;

import io.github.jbellis.brokk.analyzer.CommonTreeSitterNodeTypes;

/**
 * Constants for Java TreeSitter node type names.
 */
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

    // Package declaration
    public static final String PACKAGE_DECLARATION = "package_declaration";

    private JavaTreeSitterNodeTypes() {
    }
}
