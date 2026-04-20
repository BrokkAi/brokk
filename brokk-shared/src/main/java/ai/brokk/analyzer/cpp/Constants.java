package ai.brokk.analyzer.cpp;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.regex.Pattern;
import org.treesitter.CppNodeField;
import org.treesitter.CppNodeType;

public final class Constants {
    private Constants() {}

    public static String nodeType(CppNodeType nodeType) {
        return requireNonNull(nodeType.getType());
    }

    public static String fieldName(CppNodeField nodeField) {
        return requireNonNull(nodeField.getName());
    }

    public static final String TEST_MARKER_CAPTURE = "test.marker";
    public static final Set<String> TEST_MARKER_NAMES =
            Set.of("TEST", "TEST_F", "TEST_P", "TYPED_TEST", "TYPED_TEST_P", "TEST_CASE");
    public static final String ARGUMENT_LIST = nodeType(CppNodeType.ARGUMENT_LIST);
    public static final String CALL_EXPRESSION = nodeType(CppNodeType.CALL_EXPRESSION);
    public static final String DECLARATION_LIST = nodeType(CppNodeType.DECLARATION_LIST);
    public static final String FIELD_ARGUMENTS = fieldName(CppNodeField.ARGUMENTS);
    public static final String FIELD_BODY = fieldName(CppNodeField.BODY);
    public static final String FIELD_DECLARATOR = fieldName(CppNodeField.DECLARATOR);
    public static final String FIELD_DEFAULT_VALUE = fieldName(CppNodeField.DEFAULT_VALUE);
    public static final String FIELD_FUNCTION = fieldName(CppNodeField.FUNCTION);
    public static final String FIELD_NAME = fieldName(CppNodeField.NAME);
    public static final String FIELD_PARAMETERS = fieldName(CppNodeField.PARAMETERS);
    public static final String FIELD_TYPE = fieldName(CppNodeField.TYPE);
    public static final String TRANSLATION_UNIT = nodeType(CppNodeType.TRANSLATION_UNIT);

    public static final Pattern CPP_CONSTANT_PATTERN =
            Pattern.compile("^(?:true|false|nullptr|[-+]?\\d+(?:\\.\\d+)?|'.*'|\".*\")$");

    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
}
