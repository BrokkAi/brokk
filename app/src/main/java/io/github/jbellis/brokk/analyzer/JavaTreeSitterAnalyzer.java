package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.analyzer.java.JavaTreeSitterNodeTypes.*;

import io.github.jbellis.brokk.IProject;
import java.util.*;
import java.util.Optional;
import java.util.regex.Pattern;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

public class JavaTreeSitterAnalyzer extends TreeSitterAnalyzer {

    private final Pattern LAMBDA_REGEX = Pattern.compile("(\\$anon|\\$\\d+)");

    public JavaTreeSitterAnalyzer(IProject project) {
        super(project, Languages.JAVA, project.getExcludedDirectories());
    }

    @Override
    public Optional<String> extractClassName(String reference) {
        return ClassNameExtractor.extractForJava(reference);
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
            Set.of(
                    CLASS_DECLARATION,
                    INTERFACE_DECLARATION,
                    ENUM_DECLARATION,
                    RECORD_DECLARATION,
                    ANNOTATION_TYPE_DECLARATION),
            Set.of(METHOD_DECLARATION, CONSTRUCTOR_DECLARATION),
            Set.of(FIELD_DECLARATION, ENUM_CONSTANT),
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
                    "field.definition", SkeletonType.FIELD_LIKE),
            "", // async keyword node type
            Set.of("modifiers") // modifier node types
            );

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JAVA_SYNTAX_PROFILE;
    }

    @Override
    protected CodeUnit createCodeUnit(
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
        // Java packages are either present or not, and will be the immediate child of the `program`
        // if they are present at all
        final List<String> namespaceParts = new ArrayList<>();

        // The package may not be the first thing in the file, so we should iterate until either we find it, or we are
        // at a type node.
        TSNode maybeDeclaration = null;
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            final var child = rootNode.getChild(i);
            if (PACKAGE_DECLARATION.equals(child.getType())) {
                maybeDeclaration = child;
                break;
            } else if (JAVA_SYNTAX_PROFILE.classLikeNodeTypes().contains(child.getType())) {
                break;
            }
        }

        if (maybeDeclaration != null && PACKAGE_DECLARATION.equals(maybeDeclaration.getType())) {
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
    protected String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String bodyPlaceholder() {
        return "{...}";
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

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return "}";
    }

    @Override
    protected String nearestMethodName(String fqName) {
        // Lambdas from LSP look something like `package.Class.Method$anon$357:32`, and we want `package.Class.Method`
        var matcher = LAMBDA_REGEX.matcher(fqName);
        if (matcher.find()) {
            var match = matcher.group(1);
            return fqName.substring(0, fqName.indexOf(match));
        } else {
            return fqName;
        }
    }
}
