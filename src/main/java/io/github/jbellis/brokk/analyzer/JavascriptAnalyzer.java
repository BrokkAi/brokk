package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJavascript;

import java.util.List;
import java.util.Optional;
import java.util.Collections;
import java.util.Set;

public final class JavascriptAnalyzer extends TreeSitterAnalyzer {
    private static final TSLanguage JS_LANGUAGE = new TreeSitterJavascript();
    private static final LanguageSyntaxProfile JS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "class_expression", "class"),
            Set.of("function_declaration", "arrow_function", "method_definition", "function_expression"),
            Set.of("variable_declarator"),
            Set.of(), // JS standard decorators not captured as simple preceding nodes by current query.
            "name",       // identifierFieldName
            "body",       // bodyFieldName
            "parameters", // parametersFieldName
            "",           // returnTypeFieldName (JS doesn't have a standard named child for return type)
            "",           // typeParametersFieldName (JS doesn't have type parameters)
            java.util.Map.of( // captureConfiguration
                "class.definition", SkeletonType.CLASS_LIKE,
                "function.definition", SkeletonType.FUNCTION_LIKE,
                "field.definition", SkeletonType.FIELD_LIKE
            ),
            "async", // asyncKeywordNodeType
            Set.of() // modifierNodeTypes
    );

    public JavascriptAnalyzer(IProject project, Set<String> excludedFiles) { super(project, excludedFiles); }
    public JavascriptAnalyzer(IProject project) { this(project, Collections.emptySet()); }

    @Override protected TSLanguage getTSLanguage() { return JS_LANGUAGE; }

    @Override protected String getQueryResource() { return "treesitter/javascript.scm"; }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName, // Changed from namespaceName
                                      String classChain)
    {
        // The packageName parameter is now supplied by determinePackageName.
        return switch (captureName) {
            case "class.definition" -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case "function.definition" -> {
                String finalShortName;
                if (!classChain.isEmpty()) { // It's a method within a class structure
                    finalShortName = classChain + "." + simpleName;
                } else { // It's a top-level function in the file
                    finalShortName = simpleName;
                }
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case "field.definition" -> { // For class fields or top-level variables
                String finalShortName;
                if (classChain.isEmpty()) {
                    // For top-level variables, use a convention like "_module_.variableName"
                    // to satisfy CodeUnit.field's expectation of a "."
                    finalShortName = "_module_." + simpleName;
                } else {
                    finalShortName = classChain + "." + simpleName;
                }
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture in JavascriptAnalyzer: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                yield null;
            }
        };
    }

    @Override protected Set<String> getIgnoredCaptures() { return Set.of(); }

    @Override protected String bodyPlaceholder() { return "..."; }

    @Override
    protected SkeletonType getSkeletonTypeForCapture(String captureName) {
        // The primaryCaptureName from the query is expected to be "class.definition"
        // or "function.definition" for relevant skeleton-producing captures.
        // The primaryCaptureName from the query is expected to be "class.definition"
        // or "function.definition" for relevant skeleton-producing captures.
        // This method is now implemented in the base class using captureConfiguration from LanguageSyntaxProfile.
        // This override can be removed.
        return super.getSkeletonTypeForCapture(captureName);
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String typeParamsText, String paramsText, String returnTypeText, String indent) {
        // The 'indent' parameter is now "" when called from buildSignatureString.
        String tsReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? ": " + returnTypeText : "";
        String signature;
        String bodySuffix = " " + bodyPlaceholder();

        String nodeType = funcNode.getType();

        if ("arrow_function".equals(nodeType)) {
             signature = String.format("%s%s%s%s%s =>", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        } else { // Assumes "function_declaration", "method_definition" etc.
             signature = String.format("%s%sfunction %s%s%s", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        }
        return signature + bodySuffix; // Do not prepend indent here
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        TSNode parent = node.getParent();
        if (parent != null && !parent.isNull()) {
            // Check if 'node' is a variable_declarator and its parent is lexical_declaration or variable_declaration
            // This is for field definitions like `const a = 1;` or `export let b = 2;`
            // where `node` is the `variable_declarator` (e.g., `a = 1`).
            if (("lexical_declaration".equals(parent.getType()) || "variable_declaration".equals(parent.getType())) &&
                node.getType().equals("variable_declarator"))
            {
                TSNode declarationNode = parent; // lexical_declaration or variable_declaration
                String keyword = "";
                // The first child of lexical/variable_declaration is the keyword (const, let, var)
                TSNode keywordNode = declarationNode.getChild(0);
                if (keywordNode != null && !keywordNode.isNull()) {
                   keyword = textSlice(keywordNode, src); // "const", "let", or "var"
                }

                String exportStr = "";
                TSNode exportStatementNode = declarationNode.getParent(); // Parent of lexical/variable_declaration
                if (exportStatementNode != null && !exportStatementNode.isNull() && "export_statement".equals(exportStatementNode.getType())) {
                    exportStr = "export ";
                }
                
                // Combine export prefix and keyword
                // e.g., "export const ", "let ", "var "
                StringBuilder prefixBuilder = new StringBuilder();
                if (!exportStr.isEmpty()) {
                    prefixBuilder.append(exportStr);
                }
                if (!keyword.isEmpty()) {
                    prefixBuilder.append(keyword).append(" ");
                }
                return prefixBuilder.toString();
            }

            // Original logic for other types of nodes (e.g., class_declaration, function_declaration, arrow_function)
            // Case 1: node is class_declaration, function_declaration, etc., and its parent is an export_statement.
            if ("export_statement".equals(parent.getType())) {
                // This handles `export class Foo {}`, `export function bar() {}`
                return "export ";
            }

            // Case 2: node is the value of a variable declarator (e.g., an arrow_function or class_expression),
            // and the containing lexical_declaration or variable_declaration is exported.
            // e.g., `export const foo = () => {}` -> `node` is `arrow_function`, `parent` is `variable_declarator`.
            if ("variable_declarator".equals(parent.getType())) {
                TSNode lexicalOrVarDeclNode = parent.getParent();
                if (lexicalOrVarDeclNode != null && !lexicalOrVarDeclNode.isNull() &&
                    ("lexical_declaration".equals(lexicalOrVarDeclNode.getType()) || "variable_declaration".equals(lexicalOrVarDeclNode.getType()))) {
                    TSNode exportStatementNode = lexicalOrVarDeclNode.getParent();
                    if (exportStatementNode != null && !exportStatementNode.isNull() && "export_statement".equals(exportStatementNode.getType())) {
                        // For `export const Foo = () => {}`, this returns "export "
                        // The `const` part is not included here; it's part of the arrow function's name construction logic if needed,
                        // or implicit in the fact it's a const declaration.
                        // Current `renderFunctionDeclaration` for arrow functions does:
                        // `String.format("%s%s%s%s%s =>", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);`
                        // This correctly uses the "export " prefix.
                        return "export ";
                    }
                }
            }
        }
        return ""; // Default: no prefix
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return exportPrefix + signatureText + " {"; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // JavaScript package naming is directory-based, relative to the project root.
        // The definitionNode, rootNode, and src parameters are not used for JS package determination here.
        var projectRoot = getProject().getRoot();
        var filePath = file.absPath();
        var parentDir = filePath.getParent();

        if (parentDir == null || parentDir.equals(projectRoot)) {
            return ""; // File is in the project root
        }

        var relPath = projectRoot.relativize(parentDir);
        return relPath.toString().replace('/', '.').replace('\\', '.');
    }

    // isClassLike is now implemented in the base class using LanguageSyntaxProfile

    // buildClassMemberSkeletons is no longer directly called for parent skeleton string generation.
    // If JS needs to identify children not caught by main query for the childrenByParent map,
    // that logic would need to to be integrated into analyzeFileDeclarations or a new helper.
    // For now, assume main query captures are sufficient for JS CUs.

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JS_SYNTAX_PROFILE;
    }

}
