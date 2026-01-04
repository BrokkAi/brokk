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

    public static final String TEST_MARKER = "test_marker";

    public static final String CAPTURE_TEST_CANDIDATE_NAME = "test_candidate.name";
    public static final String CAPTURE_TEST_CANDIDATE_PARAMS = "test_candidate.params";

    public static final String PARAMETER_DECLARATION = "parameter_declaration";

    public static final String FIELD_TYPE = "type";

    public static final String POINTER_TYPE = "pointer_type";
    public static final String QUALIFIED_TYPE = "qualified_type";
    public static final String TYPE_IDENTIFIER = "type_identifier";

    public static final String TEST_FUNCTION_PREFIX = "Test";
    public static final String TESTING_T = "testing.T";
    public static final String POINTER_TESTING_T = "*testing.T";

    private GoTreeSitterNodeTypes() {
        // Utility class - no instantiation
    }
}
