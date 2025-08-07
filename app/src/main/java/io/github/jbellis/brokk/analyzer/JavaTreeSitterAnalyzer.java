package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

import java.util.*;

public class JavaTreeSitterAnalyzer extends TreeSitterAnalyzer {

    public JavaTreeSitterAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.JAVA, excludedFiles);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterJava();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/java.scm";
    }

    private static final LanguageSyntaxProfile JAVA_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "interface_declaration", "enum_declaration", "record_declaration", "annotation_type_declaration"),
            Set.of("method_declaration", "constructor_declaration"),
            Set.of("field_declaration", "enum_constant"),
            Set.of("annotation", "marker_annotation"),
            "name", // identifier field name
            "body", // body field name
            "parameters", // parameters field name
            "type", // return type field name
            "type_parameters", // type parameters field name
            Map.of( // capture configuration
                    "class.definition", SkeletonType.CLASS_LIKE,
                    "interface.definition", SkeletonType.CLASS_LIKE,
                    "enum.definition", SkeletonType.CLASS_LIKE,
                    "record.definition", SkeletonType.CLASS_LIKE,
                    "annotation.definition", SkeletonType.CLASS_LIKE, // for @interface
                    "method.definition", SkeletonType.FUNCTION_LIKE,
                    "constructor.definition", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE
            ),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
    );

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JAVA_SYNTAX_PROFILE;
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        final char delimiter = Optional.ofNullable(JAVA_SYNTAX_PROFILE.captureConfiguration().get(captureName))
                .stream().anyMatch(x -> x.equals(SkeletonType.CLASS_LIKE)) ? '$' : '.';
        final String fqName = classChain.isEmpty() ? simpleName : classChain + delimiter + simpleName;

        var skeletonType = getSkeletonTypeForCapture(captureName);
        var type = switch (skeletonType) {
            case CLASS_LIKE -> CodeUnitType.CLASS;
            case FUNCTION_LIKE -> CodeUnitType.FUNCTION;
            case FIELD_LIKE -> CodeUnitType.FIELD;
            case MODULE_STATEMENT ->  CodeUnitType.MODULE;
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
        // Java packages are either present or not, and will be the immediate child of the `program`
        // if they are present at all
        final List<String> namespaceParts = new ArrayList<>();

        final var maybeDeclaration = rootNode.getChildCount() > 0 ? rootNode.getChild(0) : null;
        if (maybeDeclaration != null && "package_declaration".equals(maybeDeclaration.getType())) {
            for (int i = 0; i < maybeDeclaration.getNamedChildCount(); i++) {
                final TSNode nameNode = maybeDeclaration.getNamedChild(i);
                if (nameNode != null && !nameNode.isNull()) {
                    String nsPart = textSlice(nameNode, src);
                    namespaceParts.add(nsPart);
                }
            }
        }
        Collections.reverse(namespaceParts);
        return String.join(".", namespaceParts);
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String bodyPlaceholder() {
        return "{...}";
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportAndModifierPrefix, String asyncPrefix, String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        var typeParams = typeParamsText.isEmpty() ? "" : typeParamsText + " ";
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        var signature = indent + exportAndModifierPrefix + typeParams + returnType + functionName + paramsText;

        var throwsNode = funcNode.getChildByFieldName("throws");
        if (throwsNode != null) {
            signature += " " + textSlice(throwsNode, src);
        }

        return signature;
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}";
    }
}
