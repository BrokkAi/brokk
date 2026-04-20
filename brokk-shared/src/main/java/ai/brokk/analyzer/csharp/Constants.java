package ai.brokk.analyzer.csharp;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import org.treesitter.CSharpNodeType;

public final class Constants {

    private Constants() {}

    public static String nodeType(CSharpNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static final Set<String> TEST_METHOD_ATTRIBUTES =
            Set.of("fact", "theory", "test", "testcase", "testmethod", "datatestmethod");

    public static final Set<String> CSHARP_ASSERTION_METHOD_NAMES = Set.of(
            "equal",
            "equals",
            "same",
            "notsame",
            "true",
            "false",
            "null",
            "notnull",
            "istrue",
            "isfalse",
            "areequal",
            "arenotequal");

    public static final Set<String> CSHARP_SHALLOW_ASSERTION_METHOD_NAMES = Set.of("null", "notnull");

    public static final String TEST_ASSERTION_KIND_CSHARP = "csharp-assertion";
    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    public static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";

    public static final String FIELD_BODY = "body";
    public static final String FIELD_ARGUMENTS = "arguments";
    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_EXPRESSION = "expression";
}
