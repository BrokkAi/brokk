package ai.brokk.analyzer.cpp;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for C++ TreeSitter node type names. */
public final class CppTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Declarations
    public static final String CONSTRUCTOR_DECLARATION = CommonTreeSitterNodeTypes.CONSTRUCTOR_DECLARATION;
    public static final String FIELD_DECLARATION = CommonTreeSitterNodeTypes.FIELD_DECLARATION;

    // ===== C++-SPECIFIC TYPES =====
    // Specifiers
    public static final String CLASS_SPECIFIER = "class_specifier";
    public static final String STRUCT_SPECIFIER = "struct_specifier";
    public static final String UNION_SPECIFIER = "union_specifier";
    public static final String ENUM_SPECIFIER = "enum_specifier";
    public static final String NOEXCEPT_SPECIFIER = "noexcept_specifier";
    public static final String VIRTUAL_SPECIFIER = "virtual_specifier";
    public static final String ATTRIBUTE_SPECIFIER = "attribute_specifier";
    public static final String ACCESS_SPECIFIER = "access_specifier";
    public static final String STORAGE_CLASS_SPECIFIER = "storage_class_specifier";
    public static final String TYPE_QUALIFIER = "type_qualifier";

    // Definitions
    public static final String NAMESPACE_DEFINITION = "namespace_definition";
    public static final String FUNCTION_DEFINITION = "function_definition";
    public static final String METHOD_DEFINITION = "method_definition";

    // Declarations
    public static final String DECLARATION = "declaration";
    public static final String DESTRUCTOR_DECLARATION = "destructor_declaration";
    public static final String PARAMETER_DECLARATION = "parameter_declaration";
    public static final String TYPE_DEFINITION = "type_definition";
    public static final String ALIAS_DECLARATION = "alias_declaration";
    public static final String USING_DECLARATION = "using_declaration";
    public static final String TYPEDEF_DECLARATION = "typedef_declaration";

    // Types and Return Types
    public static final String TRAILING_RETURN_TYPE = "trailing_return_type";

    // Other nodes
    public static final String ENUMERATOR = "enumerator";

    public static final String IMPORT_DECLARATION = "preproc_include";

    private CppTreeSitterNodeTypes() {}
}
