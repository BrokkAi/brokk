package ai.brokk.analyzer.php;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for PHP TreeSitter node type names. */
public final class PhpTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Class-like declarations
    public static final String CLASS_DECLARATION = CommonTreeSitterNodeTypes.CLASS_DECLARATION;
    public static final String INTERFACE_DECLARATION = CommonTreeSitterNodeTypes.INTERFACE_DECLARATION;

    // Method-like declarations
    public static final String METHOD_DECLARATION = CommonTreeSitterNodeTypes.METHOD_DECLARATION;

    // ===== PHP-SPECIFIC TYPES =====
    // Namespace definition
    public static final String NAMESPACE_DEFINITION = "namespace_definition";

    // Statements
    public static final String DECLARE_STATEMENT = "declare_statement";
    public static final String COMPOUND_STATEMENT = "compound_statement";

    // Class-like declarations
    public static final String TRAIT_DECLARATION = "trait_declaration";

    // Function-like declarations
    public static final String FUNCTION_DEFINITION = "function_definition";

    // Field-like declarations
    public static final String PROPERTY_DECLARATION = "property_declaration";
    public static final String CONST_DECLARATION = "const_declaration";

    // Import declarations
    public static final String IMPORT_DECLARATION = "namespace_use_declaration";

    private PhpTreeSitterNodeTypes() {}
}
