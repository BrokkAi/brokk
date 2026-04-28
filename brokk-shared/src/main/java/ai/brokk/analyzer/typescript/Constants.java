package ai.brokk.analyzer.typescript;

import static ai.brokk.analyzer.javascript.Constants.*;

import ai.brokk.analyzer.CaptureNames;
import ai.brokk.analyzer.TreeSitterAnalyzer.LanguageSyntaxProfile;
import ai.brokk.analyzer.TreeSitterAnalyzer.SkeletonType;
import java.util.Map;
import java.util.Set;
import org.treesitter.TsxNodeType;

public final class Constants {
    private Constants() {}

    public static final Set<String> FUNCTION_NODE_TYPES = Set.of(
            nodeType(TsxNodeType.FUNCTION_DECLARATION),
            nodeType(TsxNodeType.GENERATOR_FUNCTION_DECLARATION),
            nodeType(TsxNodeType.FUNCTION_SIGNATURE));

    public static final Map<String, String> CLASS_KEYWORDS = Map.of(
            nodeType(TsxNodeType.INTERFACE_DECLARATION), INTERFACE,
            nodeType(TsxNodeType.ENUM_DECLARATION), ENUM,
            nodeType(TsxNodeType.MODULE), NAMESPACE,
            nodeType(TsxNodeType.INTERNAL_MODULE), NAMESPACE,
            nodeType(TsxNodeType.AMBIENT_DECLARATION), NAMESPACE,
            nodeType(TsxNodeType.ABSTRACT_CLASS_DECLARATION), ABSTRACT_CLASS);

    public static final LanguageSyntaxProfile TS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(
                    nodeType(TsxNodeType.CLASS_DECLARATION),
                    nodeType(TsxNodeType.INTERFACE_DECLARATION),
                    nodeType(TsxNodeType.ENUM_DECLARATION),
                    nodeType(TsxNodeType.ABSTRACT_CLASS_DECLARATION),
                    nodeType(TsxNodeType.MODULE),
                    nodeType(TsxNodeType.INTERNAL_MODULE)),
            Set.of(
                    nodeType(TsxNodeType.FUNCTION_DECLARATION),
                    nodeType(TsxNodeType.METHOD_DEFINITION),
                    nodeType(TsxNodeType.ARROW_FUNCTION),
                    nodeType(TsxNodeType.GENERATOR_FUNCTION_DECLARATION),
                    nodeType(TsxNodeType.FUNCTION_SIGNATURE),
                    nodeType(TsxNodeType.METHOD_SIGNATURE),
                    nodeType(TsxNodeType.ABSTRACT_METHOD_SIGNATURE)),
            Set.of(
                    nodeType(TsxNodeType.VARIABLE_DECLARATOR),
                    nodeType(TsxNodeType.PUBLIC_FIELD_DEFINITION),
                    nodeType(TsxNodeType.PROPERTY_SIGNATURE),
                    ENUM_MEMBER,
                    nodeType(TsxNodeType.LEXICAL_DECLARATION),
                    nodeType(TsxNodeType.VARIABLE_DECLARATION)),
            Set.of(CaptureNames.CONSTRUCTOR_DEFINITION),
            Set.of(nodeType(TsxNodeType.DECORATOR)),
            CaptureNames.IMPORT_DECLARATION,
            FIELD_NAME,
            FIELD_BODY,
            FIELD_PARAMETERS,
            FIELD_RETURN_TYPE,
            FIELD_TYPE_PARAMETERS,
            Map.ofEntries(
                    Map.entry(CaptureNames.TYPE_DEFINITION, SkeletonType.CLASS_LIKE),
                    Map.entry(CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE),
                    Map.entry(CaptureNames.ARROW_FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE),
                    Map.entry(CaptureNames.VALUE_DEFINITION, SkeletonType.FIELD_LIKE),
                    Map.entry(CaptureNames.TYPEALIAS_DEFINITION, SkeletonType.ALIAS_LIKE),
                    Map.entry(CaptureNames.DECORATOR_DEFINITION, SkeletonType.UNSUPPORTED),
                    Map.entry(KEYWORD_MODIFIER_CAPTURE, SkeletonType.UNSUPPORTED)),
            ASYNC,
            Set.of(
                    EXPORT,
                    DEFAULT,
                    DECLARE,
                    ABSTRACT,
                    STATIC,
                    READONLY,
                    nodeType(TsxNodeType.ACCESSIBILITY_MODIFIER),
                    ASYNC,
                    CONST,
                    LET,
                    VAR,
                    OVERRIDE));
}
