package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryException;
import org.treesitter.TSQueryMatch;
import org.treesitter.TreeSitterJavascript;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class JavascriptAnalyzer extends TreeSitterAnalyzer {
    // JS_LANGUAGE field removed, createTSLanguage will provide new instances.
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

    public JavascriptAnalyzer(IProject project, Set<String> excludedFiles) { super(project, Language.JAVASCRIPT, excludedFiles); }
    public JavascriptAnalyzer(IProject project) { this(project, Collections.emptySet()); }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterJavascript();
    }

    @Override protected String getQueryResource() { return "treesitter/javascript.scm"; }

    @Override
    protected @Nullable CodeUnit createCodeUnit(ProjectFile file,
                                                String captureName,
                                                String simpleName,
                                                String packageName,
                                                String classChain)
    {
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
                    // For top-level variables, use filename as a prefix to ensure uniqueness
                    // and satisfy CodeUnit.field's expectation of a ".".
                    finalShortName = file.getFileName() + "." + simpleName;
                } else {
                    finalShortName = classChain + "." + simpleName;
                }
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture in JavascriptAnalyzer: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                yield null; // Explicitly yield null
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
        String inferredReturnType = returnTypeText;
        // ProjectFile currentFile = null; // Unused variable removed

        // Attempt to get current file from CU if available through funcNode context
        // For now, type inference will be based on syntax, not file extension.
        // If super.getProject().findFile(sourcePath) could be used, it would require sourcePath.

        // Infer JSX.Element return type if no explicit return type is present AND:
        // 1. It's an exported function/component starting with an uppercase letter (common React convention).
        // OR
        // 2. It's a method named "render" (classic React class component method).
        boolean isExported = !exportPrefix.trim().isEmpty();
        boolean isComponentName = !functionName.isEmpty() && Character.isUpperCase(functionName.charAt(0));
        boolean isRenderMethod = "render".equals(functionName);

        if ((isRenderMethod || (isExported && isComponentName)) && returnTypeText.isEmpty()) {
            if (returnsJsxElement(funcNode)) { // src parameter removed
                inferredReturnType = "JSX.Element";
            }
        }

        String tsReturnTypeSuffix = !inferredReturnType.isEmpty() ? ": " + inferredReturnType : "";
        String signature;
        String bodySuffix = " " + bodyPlaceholder();

        String nodeType = funcNode.getType();

        if ("arrow_function".equals(nodeType)) {
            // For arrow functions, we need to strip const/let/var from the exportPrefix
            String cleanedExportPrefix = exportPrefix;
            if (exportPrefix.contains("const")) {
                cleanedExportPrefix = exportPrefix.replace("const", "").trim();
                if (!cleanedExportPrefix.isEmpty() && !cleanedExportPrefix.endsWith(" ")) {
                    cleanedExportPrefix += " ";
                }
            } else if (exportPrefix.contains("let")) {
                cleanedExportPrefix = exportPrefix.replace("let", "").trim();
                if (!cleanedExportPrefix.isEmpty() && !cleanedExportPrefix.endsWith(" ")) {
                    cleanedExportPrefix += " ";
                }
            }
            signature = String.format("%s%s%s%s%s =>", cleanedExportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        } else { // Assumes "function_declaration", "method_definition" etc.
             signature = String.format("%s%sfunction %s%s%s", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        }
        return signature + bodySuffix; // Do not prepend indent here
    }

    private boolean isJsxNode(TSNode node) {
        if (node.isNull()) return false;
        String type = node.getType();
        return "jsx_element".equals(type) || "jsx_self_closing_element".equals(type) || "jsx_fragment".equals(type);
    }

    private boolean returnsJsxElement(TSNode funcNode) { // src parameter removed
        TSNode bodyNode = funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        if (bodyNode == null || bodyNode.isNull()) {
            return false;
        }

        // Case 1: Arrow function with implicit return: () => <div />
        if ("arrow_function".equals(funcNode.getType())) {
            if (isJsxNode(bodyNode)) { // bodyNode is the expression itself for implicit return
                return true;
            }
        }

        // Case 2: Explicit return statement: return <div />; or return (<div />);
        // We need a small query to run over the bodyNode.
        // Create a specific, local query for this check.
        // TSLanguage and TSQuery are not AutoCloseable.
        TSLanguage jsLanguage = getTSLanguage(); // Use thread-local language instance
        try {
            // Query for return statements that directly return a JSX element, or one wrapped in parentheses.
            // Each line is a separate pattern; the query matches if any of them are found.
            // The @jsx_return capture is on the JSX node itself.
            // Queries for return statements that directly return a JSX element or one wrapped in parentheses.
            // Note: Removed jsx_fragment queries as they were causing TSQueryErrorField,
            // potentially due to grammar version or query engine specifics.
            // Standard jsx_element (e.g. <></> becoming <JsxElement name={null}>) might cover fragments.
            String jsxReturnQueryStr = """
                (return_statement (jsx_element) @jsx_return)
                (return_statement (jsx_self_closing_element) @jsx_return)
                (return_statement (parenthesized_expression (jsx_element)) @jsx_return)
                (return_statement (parenthesized_expression (jsx_self_closing_element)) @jsx_return)
                """.stripIndent();
            // TSQuery and TSLanguage are not AutoCloseable by default in the used library version.
            // Ensure cursor is handled if it were AutoCloseable.
            TSQuery returnJsxQuery = new TSQuery(jsLanguage, jsxReturnQueryStr);
            TSQueryCursor cursor = new TSQueryCursor();
            cursor.exec(returnJsxQuery, bodyNode);
            TSQueryMatch match = new TSQueryMatch(); // Reusable match object
            if (cursor.nextMatch(match)) {
                    return true; // Found a JSX return
                }
        } catch (TSQueryException e) {
            // Log specific query exceptions, which usually indicate a problem with the query string itself.
            log.error("Invalid TSQuery for JSX return type inference: {}", e.getMessage(), e);
        } catch (Exception e) { // Catch other broader exceptions during query execution
            log.error("Error during JSX return type inference query execution: {}", e.getMessage(), e);
        }
        return false;
    }

    @Override
    protected List<String> getExtraFunctionComments(TSNode bodyNode, String src, @Nullable CodeUnit functionCu) {
        if (bodyNode.isNull()) {
            return List.of();
        }

        // Only apply for .jsx or .tsx files, or if JSX syntax is clearly present.
        // For simplicity, let's assume if this logic is active, it's for a JSX context.
        // A more robust check might involve checking functionCu.source().getFileName().

        Set<String> mutatedIdentifiers = new HashSet<>();
        String mutationQueryStr = """
            (assignment_expression left: (identifier) @mutated.id)
            (assignment_expression left: (member_expression property: (property_identifier) @mutated.id))
            (assignment_expression left: (subscript_expression index: _ @mutated.id))
            (update_expression argument: (identifier) @mutated.id)
            (update_expression argument: (member_expression property: (property_identifier) @mutated.id))
            """.stripIndent();

        // TSLanguage and TSQuery are not AutoCloseable.
        TSLanguage jsLanguage = getTSLanguage(); // Use thread-local language instance
        try {
            TSQuery mutationQuery = new TSQuery(jsLanguage, mutationQueryStr);
            TSQueryCursor cursor = new TSQueryCursor();
            cursor.exec(mutationQuery, bodyNode);
            TSQueryMatch match = new TSQueryMatch(); // Reusable match object
            while (cursor.nextMatch(match)) {
                for (org.treesitter.TSQueryCapture capture : match.getCaptures()) {
                    String captureName = mutationQuery.getCaptureNameForId(capture.getIndex());
                    if ("mutated.id".equals(captureName)) {
                        mutatedIdentifiers.add(textSlice(capture.getNode(), src));
                    }
                }
            }
        } catch (Exception e) { // Catch broader exceptions if TSQuery construction fails
            log.error("Error querying function body for mutations: {}", e.getMessage(), e);
        }

        if (!mutatedIdentifiers.isEmpty()) {
            List<String> sortedMutations = new ArrayList<>(mutatedIdentifiers);
            Collections.sort(sortedMutations);
            return List.of("// mutates: " + String.join(", ", sortedMutations));
        }

        return List.of();
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
    protected String formatFieldSignature(TSNode fieldNode, String src, String exportPrefix, String signatureText, String baseIndent, ProjectFile file) {
        // JavaScript field signatures shouldn't have semicolons
        var fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();
        return baseIndent + fullSignature;
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JS_SYNTAX_PROFILE;
    }

}
