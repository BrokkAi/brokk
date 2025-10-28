package ai.brokk.analyzer;

import static ai.brokk.analyzer.csharp.CSharpTreeSitterNodeTypes.*;

import ai.brokk.IProject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    static final Logger log = LoggerFactory.getLogger(CSharpAnalyzer.class);

    private static final LanguageSyntaxProfile CS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(
                    CLASS_DECLARATION,
                    INTERFACE_DECLARATION,
                    STRUCT_DECLARATION,
                    RECORD_DECLARATION,
                    RECORD_STRUCT_DECLARATION),
            Set.of(METHOD_DECLARATION, CONSTRUCTOR_DECLARATION, LOCAL_FUNCTION_STATEMENT),
            Set.of(FIELD_DECLARATION, PROPERTY_DECLARATION, EVENT_FIELD_DECLARATION),
            Set.of("attribute_list"),
            IMPORT_DECLARATION,
            "name",
            "body",
            "parameters",
            "type",
            "type_parameter_list", // typeParametersFieldName (C# generics)
            Map.of(
                    "class.definition", SkeletonType.CLASS_LIKE,
                    "function.definition", SkeletonType.FUNCTION_LIKE,
                    "constructor.definition", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE),
            "",
            Set.of());

    public CSharpAnalyzer(IProject project) {
        super(project, Languages.C_SHARP);
        log.debug("CSharpAnalyzer: Constructor called for project: {}", project);
    }

    private CSharpAnalyzer(IProject project, AnalyzerState prebuiltState) {
        super(project, Languages.C_SHARP, prebuiltState);
    }

    @Override
    protected IAnalyzer newSnapshot(AnalyzerState state) {
        return new CSharpAnalyzer(getProject(), state);
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
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file, String captureName, String simpleName, String packageName, String classChain) {
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
                    log.warn(
                            "Unhandled capture name in CSharpAnalyzer.createCodeUnit: '{}' for simple name '{}', package '{}', classChain '{}' in file {}. Returning null.",
                            captureName,
                            simpleName,
                            packageName,
                            classChain,
                            file);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn(
                    "Exception in CSharpAnalyzer.createCodeUnit for capture '{}', name '{}', file '{}', package '{}', classChain '{}': {}",
                    captureName,
                    simpleName,
                    file,
                    packageName,
                    classChain,
                    e.getMessage(),
                    e);
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
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            String src,
            String exportPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        // The 'indent' parameter is now "" when called from buildSignatureString.
        TSNode body = funcNode.getChildByFieldName("body");
        String signature;

        if (body != null && !body.isNull()) {
            signature =
                    textSlice(funcNode.getStartByte(), body.getStartByte(), src).stripTrailing();
        } else {
            TSNode paramsNode = funcNode.getChildByFieldName("parameters");
            if (paramsNode != null && !paramsNode.isNull()) {
                signature = textSlice(funcNode.getStartByte(), paramsNode.getEndByte(), src)
                        .stripTrailing();
            } else {
                signature =
                        textSlice(funcNode, src).lines().findFirst().orElse("").stripTrailing();
                log.trace(
                        "renderFunctionDeclaration for C# (node type {}): body and params not found, using fallback signature '{}'",
                        funcNode.getType(),
                        signature);
            }
        }
        return signature + " " + bodyPlaceholder();
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
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
        List<String> namespaceParts = new ArrayList<>();
        TSNode current = definitionNode.getParent();

        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            if (NAMESPACE_DECLARATION.equals(current.getType())) {
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

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            String src,
            String exportPrefix,
            String signatureText,
            String baseIndent,
            ProjectFile file) {
        var fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();

        // In C#, only actual field declarations need semicolons, not properties
        // Properties look like: public string Name { get; set; }
        // Fields look like: public string name;
        if (FIELD_DECLARATION.equals(fieldNode.getType()) && !fullSignature.endsWith(";")) {
            fullSignature += ";";
        }

        return baseIndent + fullSignature;
    }
}
