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
    public static final Set<String> TEST_MARKER_NAMES = Set.of(
            "TEST",
            "TEST_F",
            "TEST_P",
            "TYPED_TEST",
            "TYPED_TEST_P",
            "TEST_CASE",
            "SCENARIO",
            "BOOST_AUTO_TEST_CASE",
            "BOOST_FIXTURE_TEST_CASE",
            "BOOST_DATA_TEST_CASE",
            "TEST_CLASS",
            "TEST_METHOD");
    public static final String FIELD_ARGUMENTS = fieldName(CppNodeField.ARGUMENTS);
    public static final String FIELD_BODY = fieldName(CppNodeField.BODY);
    public static final String FIELD_DECLARATOR = fieldName(CppNodeField.DECLARATOR);
    public static final String FIELD_DEFAULT_VALUE = fieldName(CppNodeField.DEFAULT_VALUE);
    public static final String FIELD_FUNCTION = fieldName(CppNodeField.FUNCTION);
    public static final String FIELD_NAME = fieldName(CppNodeField.NAME);
    public static final String FIELD_PARAMETERS = fieldName(CppNodeField.PARAMETERS);
    public static final String FIELD_TYPE = fieldName(CppNodeField.TYPE);
    public static final String CONSTRUCTOR_DECLARATION = "constructor_declaration";
    public static final String DESTRUCTOR_DECLARATION = "destructor_declaration";
    public static final String METHOD_DEFINITION = "method_definition";
    public static final String NOEXCEPT_SPECIFIER = "noexcept_specifier";
    public static final String TYPEDEF_DECLARATION = "typedef_declaration";

    public static final Pattern CPP_CONSTANT_PATTERN =
            Pattern.compile("^(?:true|false|nullptr|[-+]?\\d+(?:\\.\\d+)?|'.*'|\".*\")$");

    public static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    public static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    public static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    public static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    public static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    public static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
}
