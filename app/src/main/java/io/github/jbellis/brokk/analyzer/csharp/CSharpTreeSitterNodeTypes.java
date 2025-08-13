package io.github.jbellis.brokk.analyzer.csharp;

import io.github.jbellis.brokk.analyzer.CommonTreeSitterNodeTypes;

/**
 * Constants for C# TreeSitter node type names.
 */
public final class CSharpTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like declarations
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;
    public static final String INTERFACE_DECLARATION = CommonTreeSitterNodeTypes.INTERFACE_DECLARATION;

    // Method-like declarations
    public static final String METHOD_DECLARATION = CommonTreeSitterNodeTypes.METHOD_DECLARATION;
    public static final String CONSTRUCTOR_DECLARATION = CommonTreeSitterNodeTypes.CONSTRUCTOR_DECLARATION;

    // Field-like declarations
    public static final String FIELD_DECLARATION = CommonTreeSitterNodeTypes.FIELD_DECLARATION;

    // ===== C#-SPECIFIC TYPES =====
    // Class-like declarations
    public static final String STRUCT_DECLARATION = "struct_declaration";
    public static final String RECORD_DECLARATION = "record_declaration";
    public static final String RECORD_STRUCT_DECLARATION = "record_struct_declaration";

    // Method-like declarations
    public static final String LOCAL_FUNCTION_STATEMENT = "local_function_statement";

    // Field-like declarations
    public static final String PROPERTY_DECLARATION = "property_declaration";
    public static final String EVENT_FIELD_DECLARATION = "event_field_declaration";

    // Namespace declaration
    public static final String NAMESPACE_DECLARATION = "namespace_declaration";

    private CSharpTreeSitterNodeTypes() {
    }
}
