package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.analyzer.scala.ScalaTreeSitterNodeTypes.*;

import io.github.jbellis.brokk.IProject;
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
        final String fqName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;

        var skeletonType = getSkeletonTypeForCapture(captureName);
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

        return new CodeUnit(file, type, packageName, fqName);
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        return JavaAnalyzer.determineJvmPackageName(
                rootNode, src, PACKAGE_CLAUSE, SCALA_SYNTAX_PROFILE.classLikeNodeTypes(), this::textSlice);
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "";
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return "";
    }

    @Override
    protected String bodyPlaceholder() {
        return "";
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
        var typeParams = typeParamsText.isEmpty() ? "" : typeParamsText + " ";
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        var signature = indent + exportAndModifierPrefix + typeParams + returnType + functionName + paramsText;

        var throwsNode = funcNode.getChildByFieldName("throws");
        if (throwsNode != null) {
            signature += " " + textSlice(throwsNode, src);
        }

        return signature;
    }

    private static final LanguageSyntaxProfile SCALA_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DEFINITION, OBJECT_DEFINITION, INTERFACE_DEFINITION, ENUM_DEFINITION),
            Set.of(), // METHOD_DECLARATION, CONSTRUCTOR_DECLARATION),
            Set.of(), // FIELD_DECLARATION, ENUM_CONSTANT),
            Set.of("annotation", "marker_annotation"),
            IMPORT_DECLARATION,
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    "class.definition", SkeletonType.CLASS_LIKE,
                    "object.definition", SkeletonType.CLASS_LIKE,
                    "trait.definition", SkeletonType.CLASS_LIKE,
                    "enum.definition", SkeletonType.CLASS_LIKE,
                    "method.definition", SkeletonType.FUNCTION_LIKE,
                    "constructor.definition", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE,
                    "lambda.definition", SkeletonType.FUNCTION_LIKE),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
            );
}
