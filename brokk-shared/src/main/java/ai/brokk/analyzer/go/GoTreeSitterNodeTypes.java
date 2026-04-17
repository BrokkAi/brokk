package ai.brokk.analyzer.go;

import ai.brokk.analyzer.CommonTreeSitterNodeTypes;

/** Constants for Go TreeSitter node type names. Combines common TreeSitter node types with Go-specific ones. */
public final class GoTreeSitterNodeTypes {

    // ===== COMMON TYPES (imported from CommonTreeSitterNodeTypes) =====
    // Function-like declarations
    public static final String FUNCTION_DECLARATION = CommonTreeSitterNodeTypes.FUNCTION_DECLARATION;
    public static final String METHOD_DECLARATION = CommonTreeSitterNodeTypes.METHOD_DECLARATION;

    // ===== GO-SPECIFIC TYPES =====
    // Control flow / expression nodes (used for assertion heuristics)
    public static final String IF_STATEMENT = "if_statement";
    public static final String EXPRESSION = "expression";
    public static final String BINARY_EXPRESSION = "binary_expression";
    public static final String CALL_EXPRESSION = "call_expression";

    // Common expression leaf nodes (used for tautology checks)
    public static final String IDENTIFIER = "identifier";
    public static final String SELECTOR_EXPRESSION = "selector_expression";
    public static final String PARENTHESIZED_EXPRESSION = "parenthesized_expression";

    // Type definitions
    public static final String STRUCT_TYPE = "struct_type";
    public static final String INTERFACE_TYPE = "interface_type";
    public static final String TYPE_DECLARATION = "type_declaration";
    public static final String TYPE_SPEC = "type_spec";
    public static final String TYPE_ALIAS = "type_alias";

    // Interface method
    public static final String METHOD_ELEM = "method_elem";

    public static final String IMPORT_DECLARATION = "import_declaration";

    public static final String TEST_MARKER = "test_marker";

    public static final String CAPTURE_TEST_CANDIDATE_NAME = "test_candidate.name";
    public static final String CAPTURE_TEST_CANDIDATE_PARAMS = "test_candidate.params";

    public static final String PARAMETER_DECLARATION = "parameter_declaration";

    public static final String FIELD_DECLARATION = "field_declaration";
    public static final String FIELD_IDENTIFIER = "field_identifier";
    public static final String VAR_DECLARATION = "var_declaration";
    public static final String CONST_DECLARATION = "const_declaration";
    public static final String VAR_SPEC = "var_spec";
    public static final String CONST_SPEC = "const_spec";

    public static final String FIELD_TYPE = "type";

    public static final String POINTER_TYPE = "pointer_type";
    public static final String QUALIFIED_TYPE = "qualified_type";
    public static final String TYPE_IDENTIFIER = "type_identifier";

    // Literals (best-effort node type names)
    public static final String STRING_LITERAL = "string_literal";
    public static final String CHAR_LITERAL = "char_literal";
    public static final String INTEGER_LITERAL = "integer_literal";
    public static final String FLOAT_LITERAL = "float_literal";
    public static final String BOOLEAN_LITERAL = "boolean_literal";
    public static final String NIL_LITERAL = "nil";
    public static final String TRUE_LITERAL = "true";
    public static final String FALSE_LITERAL = "false";

    // ===== Test assertion smell labels (shared string values) =====
    // These are semantic labels used in IAnalyzer.TestAssertionSmell.
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";

    public static final String TEST_FUNCTION_PREFIX = "Test";
    public static final String TESTING_T = "testing.T";
    public static final String POINTER_TESTING_T = "*testing.T";

    // ===== STATEMENTS / EXPRESSIONS (for code quality traversal) =====
    public static final String DEFER_STATEMENT = "defer_statement";
    public static final String BLOCK = "block";
    public static final String STATEMENT_LIST = "statement_list";
    public static final String RETURN_STATEMENT = "return_statement";

    // tree-sitter-go uses "func_literal" for anonymous functions.
    public static final String FUNCTION_LITERAL = "func_literal";
    public static final String EXPRESSION_STATEMENT = "expression_statement";
    public static final String NIL = "nil";

    // Tree-sitter-go uses a single "comment" node type for both line and block comments.
    public static final String COMMENT = "comment";

    private GoTreeSitterNodeTypes() {
        // Utility class - no instantiation
    }
}
