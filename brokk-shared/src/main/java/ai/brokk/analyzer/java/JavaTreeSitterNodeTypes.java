package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;
import java.util.Set;

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

    // Reference node types (used in isAccessExpression)
    public static final String METHOD_INVOCATION = "method_invocation";
    public static final String FIELD_ACCESS = "field_access";
    public static final String OBJECT_CREATION_EXPRESSION = "object_creation_expression";
    public static final String TYPE_IDENTIFIER = "type_identifier";
    public static final String SCOPED_TYPE_IDENTIFIER = "scoped_type_identifier";
    public static final String CLASS_LITERAL = "class_literal";

    // Declaration context node types
    public static final String VARIABLE_DECLARATOR = "variable_declarator";
    public static final String FORMAL_PARAMETER = "formal_parameter";
    public static final String SPREAD_PARAMETER = "spread_parameter";
    public static final String LOCAL_VARIABLE_DECLARATION = "local_variable_declaration";
    public static final String ENHANCED_FOR_STATEMENT = "enhanced_for_statement";
    public static final String CATCH_FORMAL_PARAMETER = "catch_formal_parameter";
    public static final String RESOURCE_SPECIFICATION = "resource_specification";
    public static final String RESOURCE = "resource";
    public static final String LAMBDA_EXPRESSION = "lambda_expression";
    public static final String INFERRED_PARAMETERS = "inferred_parameters";
    public static final String FORMAL_PARAMETERS = "formal_parameters";
    public static final String INSTANCEOF_EXPRESSION = "instanceof_expression";
    public static final String PATTERN = "pattern";

    // Literals
    public static final String DECIMAL_INTEGER_LITERAL = "decimal_integer_literal";
    public static final String HEX_INTEGER_LITERAL = "hex_integer_literal";
    public static final String OCTAL_INTEGER_LITERAL = "octal_integer_literal";
    public static final String BINARY_INTEGER_LITERAL = "binary_integer_literal";
    public static final String DECIMAL_FLOATING_POINT_LITERAL = "decimal_floating_point_literal";
    public static final String HEX_FLOATING_POINT_LITERAL = "hex_floating_point_literal";
    public static final String STRING_LITERAL = "string_literal";
    public static final String CHARACTER_LITERAL = "character_literal";
    public static final String BOOLEAN_LITERAL = "boolean_literal";
    public static final String NULL_LITERAL = "null_literal";

    // Keywords that can be literal values
    public static final String TRUE = "true";
    public static final String FALSE = "false";
    public static final String NULL = "null";

    public static final Set<String> DECLARATION_TYPES = Set.of(
            METHOD_DECLARATION,
            FIELD_DECLARATION,
            CLASS_DECLARATION,
            INTERFACE_DECLARATION,
            ENUM_DECLARATION,
            RECORD_DECLARATION,
            VARIABLE_DECLARATOR,
            FORMAL_PARAMETER);
    public static final Set<String> ACCESS_TYPES = Set.of(
            METHOD_INVOCATION,
            FIELD_ACCESS,
            OBJECT_CREATION_EXPRESSION,
            TYPE_IDENTIFIER,
            SCOPED_TYPE_IDENTIFIER,
            MARKER_ANNOTATION,
            ANNOTATION,
            CLASS_LITERAL,
            IMPORT_DECLARATION);

    private JavaTreeSitterNodeTypes() {}
}
