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
    public static final String MOD_ITEM = "mod_item";

    // Function-like declarations
    public static final String FUNCTION_ITEM = "function_item";
    public static final String FUNCTION_SIGNATURE_ITEM = "function_signature_item";

    // Field-like declarations
    public static final String TYPE_ITEM = "type_item";
    public static final String CONST_ITEM = "const_item";
    public static final String STATIC_ITEM = "static_item";
    public static final String ENUM_VARIANT = "enum_variant";

    // Type definitions
    public static final String GENERIC_TYPE = "generic_type";
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String SCOPED_TYPE_IDENTIFIER = "scoped_type_identifier";
    public static final String REFERENCE_TYPE = "reference_type";
    public static final String POINTER_TYPE = "pointer_type";
    public static final String ARRAY_TYPE = "array_type";
    public static final String TUPLE_TYPE = "tuple_type";

    // Other declarations
    public static final String ATTRIBUTE = "attribute";
    public static final String ATTRIBUTE_ITEM = "attribute_item";
    public static final String INNER_ATTRIBUTE = "inner_attribute";
    public static final String VISIBILITY_MODIFIER = "visibility_modifier";

    public static final String IMPORT_DECLARATION = "use_declaration";

    // ===== STATEMENTS / EXPRESSIONS (for code quality traversal) =====
    public static final String CALL_EXPRESSION = "call_expression";
    public static final String MATCH_EXPRESSION = "match_expression";
    public static final String MATCH_ARM = "match_arm";
    public static final String IF_EXPRESSION = "if_expression";
    public static final String LET_CONDITION = "let_condition";
    public static final String BLOCK = "block";
    public static final String EXPRESSION_STATEMENT = "expression_statement";
    public static final String MACRO_INVOCATION = "macro_invocation";
    public static final String PATH = "path";
    public static final String SCOPED_IDENTIFIER = "scoped_identifier";
    public static final String IDENTIFIER = "identifier";
    public static final String LINE_COMMENT = "line_comment";
    public static final String BLOCK_COMMENT = "block_comment";

    // ===== QUERY CAPTURE NAMES =====
    public static final String TEST_MARKER = "test_marker";

    private RustTreeSitterNodeTypes() {}
}
