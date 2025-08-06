package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCpp;

import java.util.*;

/**
 * C++ TreeSitter analyzer using the TreeSitter C parser.
 * Note: This uses the C grammar which can handle basic C++ constructs
 * but may not support advanced C++ features like templates, classes with complex inheritance, etc.
 * For full C++ support, a dedicated C++ grammar would be needed.
 */
public class CppTreeSitterAnalyzer extends TreeSitterAnalyzer {

    private static final LanguageSyntaxProfile CPP_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_specifier", "struct_specifier", "union_specifier", "enum_specifier"),
            Set.of("function_definition", "constructor_definition", "destructor_definition"),
            Set.of("field_declaration", "parameter_declaration"),
            Set.of("attribute_specifier"),
            "identifier",
            "compound_statement",
            "parameter_list",
            "type_specifier",
            "template_parameter_list",
            Map.of(
                    "class.definition", SkeletonType.CLASS_LIKE,
                    "struct.definition", SkeletonType.CLASS_LIKE,
                    "union.definition", SkeletonType.CLASS_LIKE,
                    "enum.definition", SkeletonType.CLASS_LIKE,
                    "function.definition", SkeletonType.FUNCTION_LIKE,
                    "constructor.definition", SkeletonType.FUNCTION_LIKE,
                    "destructor.definition", SkeletonType.FUNCTION_LIKE,
                    "method.declaration", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE
            ),
            "",
            Set.of("storage_class_specifier", "type_qualifier")
    );

    public CppTreeSitterAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.CPP_TREESITTER, excludedFiles);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterCpp();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/cpp.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return CPP_SYNTAX_PROFILE;
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
        var skeletonType = getSkeletonTypeForCapture(captureName);
        var type = switch (skeletonType) {
            case CLASS_LIKE -> CodeUnitType.CLASS;
            case FUNCTION_LIKE -> CodeUnitType.FUNCTION;
            case FIELD_LIKE -> CodeUnitType.FIELD;
            case MODULE_STATEMENT -> CodeUnitType.MODULE;
            default -> {
                log.warn("Unhandled CodeUnitType for '{}' in C++", skeletonType);
                yield CodeUnitType.CLASS;
            }
        };

        final char delimiter = switch (skeletonType) {
            case CLASS_LIKE -> '$';
            case FUNCTION_LIKE, FIELD_LIKE -> '.';
            default -> '.';
        };

        final String fqName = classChain.isEmpty() ? simpleName : classChain + delimiter + simpleName;
        return new CodeUnit(file, type, packageName, fqName);
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        var namespaceParts = new ArrayList<String>();

        var current = definitionNode;
        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            var parent = current.getParent();
            if (parent == null || parent.isNull()) {
                break;
            }
            current = parent;

            if ("namespace_definition".equals(current.getType())) {
                var nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    namespaceParts.add(textSlice(nameNode, src));
                }
            }
        }

        Collections.reverse(namespaceParts);
        return String.join("::", namespaceParts);
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
        var templateParams = typeParamsText.isEmpty() ? "" : typeParamsText + " ";
        var returnType = returnTypeText.isEmpty() ? "" : returnTypeText + " ";

        var signature = indent + exportAndModifierPrefix + templateParams + returnType + functionName + paramsText;

        var throwsNode = funcNode.getChildByFieldName("noexcept_specifier");
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
    protected boolean requiresSemicolons() {
        return true;
    }
}