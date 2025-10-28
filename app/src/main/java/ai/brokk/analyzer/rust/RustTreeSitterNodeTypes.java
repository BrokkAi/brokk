package ai.brokk.analyzer.rust;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for Rust TreeSitter node type names. */
public final class RustTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Field-like declarations
    public static final String FIELD_DECLARATION = CommonTreeSitterNodeTypes.FIELD_DECLARATION;

    // ===== RUST-SPECIFIC TYPES =====
    // Class-like declarations
    public static final String IMPL_ITEM = "impl_item";
    public static final String TRAIT_ITEM = "trait_item";
    public static final String STRUCT_ITEM = "struct_item";
    public static final String ENUM_ITEM = "enum_item";

    // Function-like declarations
    public static final String FUNCTION_ITEM = "function_item";
    public static final String FUNCTION_SIGNATURE_ITEM = "function_signature_item";

    // Field-like declarations
    public static final String CONST_ITEM = "const_item";
    public static final String STATIC_ITEM = "static_item";
    public static final String ENUM_VARIANT = "enum_variant";

    // Type definitions
    public static final String GENERIC_TYPE = "generic_type";
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String SCOPED_TYPE_IDENTIFIER = "scoped_type_identifier";

    // Other declarations
    public static final String ATTRIBUTE_ITEM = "attribute_item";
    public static final String VISIBILITY_MODIFIER = "visibility_modifier";

    public static final String IMPORT_DECLARATION = "use_declaration";

    private RustTreeSitterNodeTypes() {}
}
