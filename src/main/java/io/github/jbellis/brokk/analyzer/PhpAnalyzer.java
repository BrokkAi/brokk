package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.IProject;
import org.treesitter.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PhpAnalyzer extends TreeSitterAnalyzer {
    private static final TSLanguage PHP_LANGUAGE = new TreeSitterPhp();

    private static final String NODE_TYPE_NAMESPACE_DEFINITION = "namespace_definition";
    private static final String NODE_TYPE_PHP_TAG = "php_tag";
    private static final String NODE_TYPE_DECLARE_STATEMENT = "declare_statement";
    private static final String NODE_TYPE_COMPOUND_STATEMENT = "compound_statement";
    private static final String NODE_TYPE_REFERENCE_MODIFIER = "reference_modifier";
    private static final String NODE_TYPE_READONLY_MODIFIER = "readonly_modifier";


    private static final LanguageSyntaxProfile PHP_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "interface_declaration", "trait_declaration"), // classLikeNodeTypes
            Set.of("function_definition", "method_declaration"),                     // functionLikeNodeTypes
            Set.of("property_declaration", "const_declaration"),                     // fieldLikeNodeTypes (capturing the whole declaration)
            Set.of("attribute_group"),                                               // decoratorNodeTypes (PHP attributes)
            "name",                                                                  // identifierFieldName
            "body",                                                                  // bodyFieldName (applies to functions/methods, class body is declaration_list)
            "parameters",                                                            // parametersFieldName
            "return_type",                                                           // returnTypeFieldName (for return type declaration)
            "",                                                                      // typeParametersFieldName (PHP doesn't have generics)
            java.util.Map.of(                                                        // captureConfiguration
                    "class.definition", SkeletonType.CLASS_LIKE,
                    "interface.definition", SkeletonType.CLASS_LIKE,
                    "trait.definition", SkeletonType.CLASS_LIKE,
                    "function.definition", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE,
                    "attribute.definition", SkeletonType.UNSUPPORTED // Attributes are handled by getPrecedingDecorators
            ),
            "",                                                                      // asyncKeywordNodeType (PHP has no async/await keywords for functions)
            Set.of("visibility_modifier", "static_modifier", "abstract_modifier", "final_modifier", NODE_TYPE_READONLY_MODIFIER) // modifierNodeTypes
    );

    private final Map<ProjectFile, String> fileScopedPackageNames = new ConcurrentHashMap<>();
    private static TSQuery PHP_NAMESPACE_QUERY;

    static {
        try {
            PHP_NAMESPACE_QUERY = new TSQuery(PHP_LANGUAGE, "(namespace_definition name: (namespace_name) @nsname)");
        } catch (Exception e) { // TSQuery constructor can throw various exceptions including TSException
            log.error("Failed to compile PHP namespace query", e);
            PHP_NAMESPACE_QUERY = null;
        }
    }

    public PhpAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, excludedFiles);
    }

    public PhpAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return PHP_LANGUAGE;
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/php.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return PHP_SYNTAX_PROFILE;
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName,
                                      String classChain) {
        return switch (captureName) {
            case "class.definition", "interface.definition", "trait.definition" -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case "function.definition" -> { // Covers global functions and class methods
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case "field.definition" -> { // Covers class properties, class constants, and global constants
                String finalShortName;
                if (classChain.isEmpty()) { // Global constant
                    finalShortName = "_module_." + simpleName;
                } else { // Class property or class constant
                    finalShortName = classChain + "." + simpleName;
                }
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                // Attributes are handled by decorator logic, not direct CUs.
                // Namespace definitions are used by determinePackageName.
                // The "namespace.name" capture from the main query is now part of getIgnoredCaptures
                // as namespace processing is handled by computeFilePackageName with its own query.
                if (!"attribute.definition".equals(captureName) &&
                    !"namespace.definition".equals(captureName) && // Main query's namespace.definition
                    !"namespace.name".equals(captureName)) {       // Main query's namespace.name
                     log.debug("Ignoring capture in PhpAnalyzer: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                }
                yield null;
            }
        };
    }

    private String computeFilePackageName(ProjectFile file, TSNode rootNode, String src) {
        if (PHP_NAMESPACE_QUERY == null) {
            log.warn("PHP namespace query was not initialized. Cannot determine package name for {}", file);
            return ""; // Fallback if query compilation failed
        }
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(PHP_NAMESPACE_QUERY, rootNode);
        TSQueryMatch match = new TSQueryMatch(); // Reusable match object

        if (cursor.nextMatch(match)) { // Assuming one namespace per file, take the first
            for (TSQueryCapture capture : match.getCaptures()) {
                // Check capture name using query's method, not hardcoded string if IDs could change
                if ("nsname".equals(PHP_NAMESPACE_QUERY.getCaptureNameForId(capture.getIndex()))) {
                    TSNode nameNode = capture.getNode();
                    if (nameNode != null) { // No need for !nameNode.isNull() if we just textSlice
                        return textSlice(nameNode, src).replace('\\', '.');
                    }
                }
            }
        }
        // Fallback to manual scan if query fails or no match, though query is preferred
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TSNode current = rootNode.getChild(i);
            if (current != null && NODE_TYPE_NAMESPACE_DEFINITION.equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null) {
                    return textSlice(nameNode, src).replace('\\', '.');
                }
            }
            if (current != null &&
                !NODE_TYPE_PHP_TAG.equals(current.getType()) &&
                !NODE_TYPE_NAMESPACE_DEFINITION.equals(current.getType()) &&
                !NODE_TYPE_DECLARE_STATEMENT.equals(current.getType()) && i > 5) {
                break; // Stop searching after a few top-level elements
            }
        }
        return ""; // No namespace found
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // definitionNode is not used here as package is file-scoped.

        // If this.fileScopedPackageNames is null, it means this method is being called
        // from the superclass (TreeSitterAnalyzer) constructor, before this PhpAnalyzer
        // instance's fields (like fileScopedPackageNames) have been initialized.
        // In this specific scenario, we cannot use the instance cache.
        // We must compute the package name directly.
        if (this.fileScopedPackageNames == null) {
            return computeFilePackageName(file, rootNode, src);
        }

        // If fileScopedPackageNames is not null, the PhpAnalyzer instance is fully initialized,
        // and we can use the caching mechanism.
        return fileScopedPackageNames.computeIfAbsent(file, f -> computeFilePackageName(f, rootNode, src));
    }


    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        if (cu.isClass()) { // CodeUnit.cls is used for class, interface, trait
            boolean isEmptyCuBody = childrenByParent.getOrDefault(cu, List.of()).isEmpty();
            if (isEmptyCuBody) {
                return ""; // Closer already handled by renderClassHeader for empty bodies
            }
            return "}";
        }
        return "";
    }

    @Override
    protected String getLanguageSpecificIndent() {
        return "  ";
    }

    private String extractModifiers(TSNode node, String src) {
        List<String> modifiers = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child == null || child.isNull()) continue;

            String type = child.getType();
            if (PHP_SYNTAX_PROFILE.modifierNodeTypes().contains(type)) { // readonly_modifier is now in the set
                 modifiers.add(textSlice(child, src));
            } else if ("name".equals(child.getType()) ||
                       "variable_name".equals(child.getType()) ||
                       "formal_parameters".equals(child.getType()) ||
                       NODE_TYPE_COMPOUND_STATEMENT.equals(child.getType()) || // body
                       "declaration_list".equals(child.getType()) || // class body
                       "type_declaration".equals(child.getType()) || // return type
                       "base_clause".equals(child.getType()) || // extends
                       "interface_clause".equals(child.getType()) || // implements
                       "use_trait_clause".equals(child.getType()) || // use Trait
                       "property_element".equals(child.getType()) ||
                       "const_element".equals(child.getType()) ||
                       "function".equals(type) || // keyword
                       "class".equals(type) || // keyword
                       "interface".equals(type) || // keyword
                       "trait".equals(type) // keyword
            ) {
                break;
            }
        }
        if (modifiers.isEmpty()) return "";
        return String.join(" ", modifiers) + " ";
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        TSNode bodyNode = classNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        boolean isEmptyBody = (bodyNode == null || bodyNode.getNamedChildCount() == 0); // bodyNode.isNull() check removed
        String suffix = isEmptyBody ? " { }" : " {";
        
        return signatureText.stripTrailing() + suffix;
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix,
                                               String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        // Attributes are handled by the base class's buildSignatureString via getPrecedingDecorators.
        // exportPrefix and asyncPrefix are "" for PHP. indent is also "" at this stage from base.
        String modifiers = extractModifiers(funcNode, src);
        
        String ampersand = "";
        TSNode referenceModifierNode = null;
        // Iterate children to find reference_modifier, as its position can vary slightly.
        for (int i = 0; i < funcNode.getChildCount(); i++) {
            TSNode child = funcNode.getChild(i);
            // Check for Java null before calling getType or other methods on child
            if (child != null && NODE_TYPE_REFERENCE_MODIFIER.equals(child.getType())) {
                referenceModifierNode = child;
                break;
            }
        }

        if (referenceModifierNode != null) { // No need for !referenceModifierNode.isNull()
            ampersand = textSlice(referenceModifierNode, src).trim();
        }

        String formattedReturnType = "";
        if (returnTypeText != null && !returnTypeText.isEmpty()) {
            formattedReturnType = ": " + returnTypeText.strip();
        }

        String ampersandPart = ampersand.isEmpty() ? "" : ampersand; 

        String mainSignaturePart = String.format("%sfunction %s%s%s%s",
                                         modifiers,
                                         ampersandPart, 
                                         functionName,
                                         paramsText, 
                                         formattedReturnType).stripTrailing();
        
        TSNode bodyNode = funcNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        // If bodyNode is null or not a compound statement, it's an abstract/interface method.
        if (bodyNode != null && !bodyNode.isNull() && NODE_TYPE_COMPOUND_STATEMENT.equals(bodyNode.getType())) {
            return mainSignaturePart + " { " + bodyPlaceholder() + " }";
        } else {
            // Abstract method or interface method (no body, ends with ';')
            return mainSignaturePart + ";";
        }
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        return "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // namespace.definition and namespace.name from the main query are ignored
        // as namespace processing is now handled by computeFilePackageName.
        // attribute.definition is handled by decorator logic in base class.
        return Set.of("namespace.definition", "namespace.name", "attribute.definition");
    }

}
