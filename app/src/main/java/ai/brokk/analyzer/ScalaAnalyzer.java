package ai.brokk.analyzer;

import static ai.brokk.analyzer.scala.ScalaTreeSitterNodeTypes.*;

import ai.brokk.IProject;
import java.util.*;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterScala;

public class ScalaAnalyzer extends TreeSitterAnalyzer {

    public ScalaAnalyzer(IProject project) {
        super(project, Languages.SCALA);
    }

    private ScalaAnalyzer(IProject project, AnalyzerState state) {
        super(project, Languages.SCALA, state);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state) {
        return new ScalaAnalyzer(getProject(), state);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterScala();
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return SCALA_SYNTAX_PROFILE;
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/scala.scm";
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        var effectiveSimpleName = simpleName;
        var skeletonType = getSkeletonTypeForCapture(captureName);

        if (CaptureNames.CONSTRUCTOR_DEFINITION.equals(captureName)) {
            // This is a primary constructor, which is matched against the class name. This constructor is "implicit"
            // and needs to be created explicitly as follows.
            effectiveSimpleName = simpleName + "." + simpleName;
        } else if (simpleName.equals("this") && skeletonType.equals(SkeletonType.FUNCTION_LIKE)) {
            // This is a secondary constructor, which is named `this`. The simple name should be the class name.
            // The classChain is the simple name of the enclosing class.
            if (!classChain.isEmpty()) {
                var lastDot = classChain.lastIndexOf('.');
                effectiveSimpleName = lastDot < 0 ? classChain : classChain.substring(lastDot + 1);
            }
        }

        final String shortName = classChain.isEmpty() ? effectiveSimpleName : classChain + "." + effectiveSimpleName;

        var type =
                switch (skeletonType) {
                    case CLASS_LIKE -> CodeUnitType.CLASS;
                    case FUNCTION_LIKE -> CodeUnitType.FUNCTION;
                    case FIELD_LIKE -> CodeUnitType.FIELD;
                    case MODULE_STATEMENT -> CodeUnitType.MODULE;
                    default -> {
                        // This shouldn't be reached if captureConfiguration is exhaustive
                        log.warn("Unhandled CodeUnitType for '{}'", skeletonType);
                        yield CodeUnitType.CLASS;
                    }
                };

        return new CodeUnit(file, type, packageName, shortName);
    }

    @Override
    protected String determineClassName(String nodeType, String shortName) {
        if (OBJECT_DEFINITION.equals(nodeType)) {
            // Companion objects append '$' on a bytecode level to avoid naming conflicts
            return shortName + "$";
        } else {
            return shortName;
        }
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        return JavaAnalyzer.determineJvmPackageName(
                rootNode, src, PACKAGE_CLAUSE, SCALA_SYNTAX_PROFILE.classLikeNodeTypes(), this::textSlice);
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}"; // We'll stick to Scala 2 closers
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return signatureText + " {"; // For consistency with closers, we need to open with Scala 2-style braces
    }

    @Override
    protected String bodyPlaceholder() {
        return "= {...}";
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            String src,
            String exportAndModifierPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        var paramSb = new StringBuilder();
        for (int i = 0; i < funcNode.getChildCount(); i++) {
            var nodeKind = funcNode.getFieldNameForChild(i);
            var child = funcNode.getChild(i);
            if ("parameters".equals(nodeKind)) {
                paramSb.append(textSlice(child, src));
            }
        }
        var allParamsText = paramSb.toString();

        var typeParams = typeParamsText.isEmpty() ? "" : typeParamsText;
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        return indent + exportAndModifierPrefix + "def " + functionName + typeParams + allParamsText + ": " + returnType
                + bodyPlaceholder();
    }

    private static final LanguageSyntaxProfile SCALA_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DEFINITION, OBJECT_DEFINITION, INTERFACE_DEFINITION, ENUM_DEFINITION),
            Set.of(FUNCTION_DEFINITION),
            Set.of(VAL_DEFINITION, VAR_DEFINITION, SIMPLE_ENUM_CASE),
            Set.of("annotation", "marker_annotation"),
            IMPORT_DECLARATION,
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "return_type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.OBJECT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.TRAIT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.ENUM_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.LAMBDA_DEFINITION, SkeletonType.FUNCTION_LIKE),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
            );
}
