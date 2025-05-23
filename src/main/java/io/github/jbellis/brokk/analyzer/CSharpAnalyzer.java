package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.util.Collections;
import java.util.Set;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    protected static final Logger log = LoggerFactory.getLogger(CSharpAnalyzer.class);

    // CS_LANGUAGE field removed, createTSLanguage will provide new instances.
    private static final LanguageSyntaxProfile CS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "interface_declaration", "struct_declaration", "record_declaration", "record_struct_declaration"),
            Set.of("method_declaration", "constructor_declaration", "local_function_statement"),
            Set.of("field_declaration", "property_declaration", "event_field_declaration"),
            Set.of("attribute_list"),
            "name",         // identifierFieldName
            "body",         // bodyFieldName
            "parameters",   // parametersFieldName
            "type",         // returnTypeFieldName (used by method_declaration for its return type node)
            java.util.Map.of( // captureConfiguration
                "class.definition", SkeletonType.CLASS_LIKE,
                "function.definition", SkeletonType.FUNCTION_LIKE,
                "constructor.definition", SkeletonType.FUNCTION_LIKE,
                "field.definition", SkeletonType.FIELD_LIKE
            ),
            "", // asyncKeywordNodeType (C# async is a modifier, handled by textSlice not as a distinct first child type)
            Set.of() // modifierNodeTypes
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
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName, // Changed from namespaceName
                                      String classChain) {
        // The packageName parameter is now supplied by determinePackageName.
        // The classChain parameter is used for Joern-style short name generation.
        // Captures for class-like (class, struct, interface) constructs are unified to "class.definition".

        CodeUnit result;
        try {
            result = switch (captureName) {
                case "class.definition" -> { // Covers class, interface, struct
                    String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                    yield CodeUnit.cls(file, packageName, finalShortName);
                }
                case "function.definition" -> { // simpleName is method name, classChain is FQ short name of owning class
                    String finalShortName = classChain + "." + simpleName;
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "constructor.definition" -> { // simpleName is class name, classChain is FQ short name of owning class
                    String finalShortName = classChain + ".<init>";
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "field.definition" -> { // simpleName is field name, classChain is FQ short name of owning class
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

    // getTopLevelBlockerNodeTypes is removed as the base method in TreeSitterAnalyzer is removed.

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
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
        return signature + " " + bodyPlaceholder(); // Do not prepend indent here
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return signatureText + " {"; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        // C# classes, interfaces, structs, enums, namespaces use { }
        return cu.isClass() ? "}" : ""; // Simplified: assuming only classes need closers for now
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // C# namespaces are determined by traversing up from the definition node
        // to find enclosing namespace_declaration nodes.
        // The 'file' parameter is not used here as namespace is derived from AST content.
        java.util.List<String> namespaceParts = new java.util.ArrayList<>();
        TSNode current = definitionNode.getParent(); // Start from the parent of the definition node

        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            if ("namespace_declaration".equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    String nsPart = textSlice(nameNode, src);
                    namespaceParts.add(nsPart); // Added from innermost to outermost
                }
            }
            current = current.getParent();
        }
        Collections.reverse(namespaceParts); // Reverse to get outermost.innermost
        return String.join(".", namespaceParts);
    }

    // isClassLike is now implemented in the base class using LanguageSyntaxProfile

    // buildClassMemberSkeletons is no longer directly called for parent skeleton string generation.

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return CS_SYNTAX_PROFILE;
    }
}
