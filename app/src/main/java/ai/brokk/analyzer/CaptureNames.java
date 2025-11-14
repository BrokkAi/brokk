package ai.brokk.analyzer;

/**
 * Constants for TreeSitter query capture names used across language analyzers.
 * These capture names are defined in the .scm query files and referenced in analyzer implementations.
 */
public final class CaptureNames {
    // Common captures used across multiple languages
    public static final String CLASS_DEFINITION = "class.definition";
    public static final String FUNCTION_DEFINITION = "function.definition";
    public static final String FIELD_DEFINITION = "field.definition";
    public static final String METHOD_DEFINITION = "method.definition";
    public static final String CONSTRUCTOR_DEFINITION = "constructor.definition";

    // Type-related captures
    public static final String INTERFACE_DEFINITION = "interface.definition";
    public static final String ENUM_DEFINITION = "enum.definition";
    public static final String STRUCT_DEFINITION = "struct.definition";
    public static final String UNION_DEFINITION = "union.definition";
    public static final String TYPE_DEFINITION = "type.definition";
    public static final String TYPEALIAS_DEFINITION = "typealias.definition";
    public static final String TYPEDEF_DEFINITION = "typedef.definition";
    public static final String RECORD_DEFINITION = "record.definition";
    public static final String TRAIT_DEFINITION = "trait.definition";
    public static final String OBJECT_DEFINITION = "object.definition";
    public static final String IMPL_DEFINITION = "impl.definition";

    // Function-like captures
    public static final String LAMBDA_DEFINITION = "lambda.definition";
    public static final String ARROW_FUNCTION_DEFINITION = "arrow_function.definition";
    public static final String DESTRUCTOR_DEFINITION = "destructor.definition";

    // Attribute/metadata captures
    public static final String ANNOTATION_DEFINITION = "annotation.definition";
    public static final String ATTRIBUTE_DEFINITION = "attribute.definition";
    public static final String DECORATOR_DEFINITION = "decorator.definition";

    // Namespace/organization captures
    public static final String NAMESPACE_DEFINITION = "namespace.definition";

    // Variable/constant captures
    public static final String VARIABLE_DEFINITION = "variable.definition";
    public static final String CONSTANT_DEFINITION = "constant.definition";
    public static final String VALUE_DEFINITION = "value.definition";
    public static final String USING_DEFINITION = "using.definition";

    private CaptureNames() {
        // Utility class, no instantiation
    }
}
