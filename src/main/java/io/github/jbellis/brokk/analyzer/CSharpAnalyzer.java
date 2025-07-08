package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.util.Collections;
import java.util.Set;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CSharpAnalyzer.class);

    private static final LanguageSyntaxProfile CS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "interface_declaration", "struct_declaration", "record_declaration", "record_struct_declaration"),
            Set.of("method_declaration", "constructor_declaration", "local_function_statement"),
            Set.of("field_declaration", "property_declaration", "event_field_declaration"),
            Set.of("attribute_list"),
            "name",
            "body",
            "parameters",
            "type",
            "type_parameter_list", // typeParametersFieldName (C# generics)
            java.util.Map.of(
                "class.definition", SkeletonType.CLASS_LIKE,
                "function.definition", SkeletonType.FUNCTION_LIKE,
                "constructor.definition", SkeletonType.FUNCTION_LIKE,
                "field.definition", SkeletonType.FIELD_LIKE
            ),
            "",
            Set.of()
    );

    public CSharpAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.C_SHARP, excludedFiles);
        log.debug("CSharpAnalyzer: Constructor called for project: {} with {} excluded files", project, excludedFiles.size());
    }

    public CSharpAnalyzer(IProject project) {
        this(project, Collections.emptySet());
        log.debug("CSharpAnalyzer: Constructor called for project: {}", project);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterCSharp();
    }

    @Override
    protected String getQueryResource() {
        var resource = "treesitter/c_sharp.scm";
        log.trace("CSharpAnalyzer: getQueryResource() returning: {}", resource);
        return resource;
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(ProjectFile file,
                                                String captureName,
                                                String simpleName,
                                                String packageName,
                                                String classChain) {
        CodeUnit result;
        try {
            result = switch (captureName) {
                case "class.definition" -> {
                    String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                    yield CodeUnit.cls(file, packageName, finalShortName);
                }
                case "function.definition" -> {
                    String finalShortName = classChain + "." + simpleName;
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "constructor.definition" -> {
                    String finalShortName = classChain + ".<init>";
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "field.definition" -> {
                    String finalShortName = classChain + "." + simpleName;
                    yield CodeUnit.field(file, packageName, finalShortName);
                }
                default -> {
                    log.warn("Unhandled capture name in CSharpAnalyzer.createCodeUnit: '{}' for simple name '{}', package '{}', classChain '{}' in file {}. Returning null.",
                             captureName, simpleName, packageName, classChain, file);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Exception in CSharpAnalyzer.createCodeUnit for capture '{}', name '{}', file '{}', package '{}', classChain '{}': {}",
                     captureName, simpleName, file, packageName, classChain, e.getMessage(), e);
            return null;
        }
        log.trace("CSharpAnalyzer.createCodeUnit: returning {}", result);
        return result;
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // C# query explicitly captures attributes/annotations to ignore them
        var ignored = Set.of("annotation");
        log.trace("CSharpAnalyzer: getIgnoredCaptures() returning: {}", ignored);
        return ignored;
    }

    @Override
    protected String bodyPlaceholder() {
        var placeholder = "{ … }";
        log.trace("CSharpAnalyzer: bodyPlaceholder() returning: {}", placeholder);
        return placeholder;
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        // The 'indent' parameter is now "" when called from buildSignatureString.
        TSNode body = funcNode.getChildByFieldName("body");
        String signature;

        if (body != null && !body.isNull()) {
            signature = textSlice(funcNode.getStartByte(), body.getStartByte(), src).stripTrailing();
        } else {
            TSNode paramsNode = funcNode.getChildByFieldName("parameters");
            if (paramsNode != null && !paramsNode.isNull()) {
                 signature = textSlice(funcNode.getStartByte(), paramsNode.getEndByte(), src).stripTrailing();
            } else {
                 signature = textSlice(funcNode, src).lines().findFirst().orElse("").stripTrailing();
                 log.trace("renderFunctionDeclaration for C# (node type {}): body and params not found, using fallback signature '{}'", funcNode.getType(), signature);
            }
        }
        return signature + " " + bodyPlaceholder();
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // C# namespaces are determined by traversing up from the definition node
        // to find enclosing namespace_declaration nodes.
        // The 'file' parameter is not used here as namespace is derived from AST content.
        java.util.List<String> namespaceParts = new java.util.ArrayList<>();
        TSNode current = definitionNode.getParent();

        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            if ("namespace_declaration".equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    String nsPart = textSlice(nameNode, src);
                    namespaceParts.add(nsPart);
                }
            }
            current = current.getParent();
        }
        Collections.reverse(namespaceParts);
        return String.join(".", namespaceParts);
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return CS_SYNTAX_PROFILE;
    }

}
