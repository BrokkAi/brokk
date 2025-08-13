package io.github.jbellis.brokk.analyzer;

/**
 * Common TreeSitter node type names shared across multiple language grammars.
 */
public final class CommonTreeSitterNodeTypes {

    // ===== CLASS-LIKE DECLARATIONS =====
    /** Object-oriented class definition */
    public static final String CLASS_DECLARATION = "class_declaration";

    /** Interface definition (Java, C#, TypeScript, PHP) */
    public static final String INTERFACE_DECLARATION = "interface_declaration";

    /** Enumeration definition */
    public static final String ENUM_DECLARATION = "enum_declaration";

    // ===== FUNCTION-LIKE DECLARATIONS =====
    /** Regular function declaration */
    public static final String FUNCTION_DECLARATION = "function_declaration";

    /** Method within a class */
    public static final String METHOD_DECLARATION = "method_declaration";

    /** Constructor declaration */
    public static final String CONSTRUCTOR_DECLARATION = "constructor_declaration";

    /** Arrow function (JavaScript/TypeScript) */
    public static final String ARROW_FUNCTION = "arrow_function";

    /** Method definition (JavaScript/TypeScript) */
    public static final String METHOD_DEFINITION = "method_definition";

    // ===== FIELD-LIKE DECLARATIONS =====
    /** Field/property declaration within a class */
    public static final String FIELD_DECLARATION = "field_declaration";

    /** Variable declarator (JavaScript/TypeScript) */
    public static final String VARIABLE_DECLARATOR = "variable_declarator";

    /** Variable declaration statement */
    public static final String VARIABLE_DECLARATION = "variable_declaration";

    /** Lexical declaration (let/const in JS/TS) */
    public static final String LEXICAL_DECLARATION = "lexical_declaration";

    // ===== MODIFIERS AND DECORATORS =====
    /** Decorator/annotation */
    public static final String DECORATOR = "decorator";

    // ===== STATEMENTS =====
    /** Export statement (JavaScript/TypeScript) */
    public static final String EXPORT_STATEMENT = "export_statement";

    private CommonTreeSitterNodeTypes() {
    }
}
