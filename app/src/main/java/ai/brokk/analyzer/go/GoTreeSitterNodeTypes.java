package ai.brokk.analyzer.go;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for Go TreeSitter node type names. Combines common TreeSitter node types with Go-specific ones. */
public final class GoTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Function-like declarations
    public static final String FUNCTION_DECLARATION = CommonTreeSitterNodeTypes.FUNCTION_DECLARATION;
    public static final String METHOD_DECLARATION = CommonTreeSitterNodeTypes.METHOD_DECLARATION;

    // ===== GO-SPECIFIC TYPES =====
    // Type definitions
    public static final String STRUCT_TYPE = "struct_type";
    public static final String INTERFACE_TYPE = "interface_type";
    public static final String TYPE_SPEC = "type_spec";

    // Interface method
    public static final String METHOD_ELEM = "method_elem";

    public static final String IMPORT_DECLARATION = "import_declaration";

    private GoTreeSitterNodeTypes() {
        // Utility class - no instantiation
    }
}
