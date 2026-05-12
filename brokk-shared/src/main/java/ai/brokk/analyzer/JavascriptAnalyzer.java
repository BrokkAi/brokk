package ai.brokk.analyzer;

import static ai.brokk.analyzer.javascript.Constants.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.ICoreProject;
import java.util.*;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryException;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TsxNodeType;

public class JavascriptAnalyzer extends JsTsAnalyzer {
    private static final LanguageSyntaxProfile JS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(nodeType(TsxNodeType.CLASS_DECLARATION), CLASS_EXPRESSION, nodeType(TsxNodeType.CLASS_)),
            Set.of(
                    nodeType(TsxNodeType.FUNCTION_DECLARATION),
                    nodeType(TsxNodeType.ARROW_FUNCTION),
                    nodeType(TsxNodeType.METHOD_DEFINITION),
                    nodeType(TsxNodeType.FUNCTION_EXPRESSION)),
            Set.of(nodeType(TsxNodeType.VARIABLE_DECLARATOR)),
            Set.of(), // JS standard decorators not captured as simple preceding nodes by current query.
            Set.of(),
            CaptureNames.IMPORT_DECLARATION,
            FIELD_NAME, // identifierFieldName
            FIELD_BODY, // bodyFieldName
            FIELD_PARAMETERS, // parametersFieldName
            "", // returnTypeFieldName (JS doesn't have a standard named child for return type)
            "", // typeParametersFieldName (JS doesn't have type parameters)
            Map.of(
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.ARROW_FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.VALUE_DEFINITION, SkeletonType.FIELD_LIKE),
            ASYNC, // asyncKeywordNodeType
            Set.of() // modifierNodeTypes
            );

    public JavascriptAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public JavascriptAnalyzer(ICoreProject project, ProgressListener listener) {
        super(project, Languages.JAVASCRIPT, listener);
    }

    private JavascriptAnalyzer(
            ICoreProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.JAVASCRIPT, state, listener, cache);
    }

    public static JavascriptAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new JavascriptAnalyzer(project, state, listener, null);
    }

    @Override
    protected JavascriptAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new JavascriptAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForJsTs(reference);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterJavascript();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/javascript/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/javascript/imports.scm");
            case IDENTIFIERS -> Optional.of("treesitter/javascript/identifiers.scm");
        };
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(
            ProjectFile file,
            String captureName,
            String simpleName,
            String packageName,
            String classChain,
            List<ScopeSegment> scopeChain,
            @Nullable TSNode definitionNode,
            SkeletonType skeletonType) {
        return switch (captureName) {
            case CaptureNames.CLASS_DEFINITION -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case CaptureNames.FUNCTION_DEFINITION, CaptureNames.ARROW_FUNCTION_DEFINITION -> {
                String finalShortName;
                if (!classChain.isEmpty()) { // It's a method within a class structure
                    finalShortName = classChain + "." + simpleName;
                } else { // It's a top-level function in the file
                    finalShortName = simpleName;
                }
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case CaptureNames.FIELD_DEFINITION,
                    CaptureNames.VALUE_DEFINITION -> { // For class fields or top-level variables
                String finalShortName;
                if (classChain.isEmpty()) {
                    // For top-level variables, use the filename as a container to ensure a "." is present
                    // and to prevent collisions across files in the same package.
                    finalShortName = file.getFileName() + "." + simpleName;
                } else {
                    finalShortName = classChain + "." + simpleName;
                }
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug(
                        "Ignoring capture in JavascriptAnalyzer: {} with name: {} and classChain: {}",
                        captureName,
                        simpleName,
                        classChain);
                yield null; // Explicitly yield null
            }
        };
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        return Set.of();
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected ResolvedNodes resolveSignatureNodes(
            TSNode definitionNode, String simpleName, SkeletonType refined, SourceContent sourceContent) {
        TSNode nodeForSignature = definitionNode;
        TSNode nodeForContent = definitionNode;

        // 1. Unwrap export statement
        if (nodeType(TsxNodeType.EXPORT_STATEMENT).equals(definitionNode.getType())) {
            TSNode declarationInExport = definitionNode.getChildByFieldName(FIELD_DECLARATION);
            if (declarationInExport != null) {
                nodeForSignature = declarationInExport;
                nodeForContent = declarationInExport;
            }
        }

        // 2. Unwrap variable declaration to specific declarator for content/body extraction
        if (refined == SkeletonType.FIELD_LIKE || refined == SkeletonType.FUNCTION_LIKE) {
            String nodeType = nodeForContent.getType();
            if (nodeType(TsxNodeType.LEXICAL_DECLARATION).equals(nodeType)
                    || nodeType(TsxNodeType.VARIABLE_DECLARATION).equals(nodeType)) {
                // Find the variable_declarator child that matches the simpleName
                for (TSNode child : nodeForContent.getChildren()) {
                    if (nodeType(TsxNodeType.VARIABLE_DECLARATOR).equals(child.getType())) {
                        TSNode nameNode = child.getChildByFieldName(
                                getLanguageSyntaxProfile().identifierFieldName());
                        if (nameNode != null
                                && sourceContent.substringFrom(nameNode).equals(simpleName)) {
                            nodeForContent = child;
                            break;
                        }
                    }
                }
            }
        }

        return new ResolvedNodes(nodeForSignature, nodeForContent);
    }

    @Override
    protected boolean shouldMergeSignaturesForSameFqn() {
        return true;
    }

    @Override
    protected String renderFunctionDeclaration(
            TSNode funcNode,
            SourceContent sourceContent,
            String exportPrefix,
            String asyncPrefix,
            String functionName,
            String typeParamsText,
            String paramsText,
            String returnTypeText,
            String indent) {
        // The 'indent' parameter is now "" when called from buildSignatureString.
        String inferredReturnType = returnTypeText;

        // Infer JSX.Element return type if no explicit return type is present AND:
        // 1. It's an exported function/component starting with an uppercase letter (common React convention).
        // OR
        // 2. It's a method named "render" (classic React class component method).
        boolean isExported = exportPrefix.trim().startsWith("export");
        boolean isComponentName = !functionName.isEmpty() && Character.isUpperCase(functionName.charAt(0));
        boolean isRenderMethod = "render".equals(functionName);

        if ((isRenderMethod || (isExported && isComponentName)) && returnTypeText.isEmpty()) {
            if (returnsJsxElement(funcNode, sourceContent)) {
                inferredReturnType = "JSX.Element";
            }
        }

        String tsReturnTypeSuffix = !inferredReturnType.isEmpty() ? ": " + inferredReturnType : "";
        String signature;
        String bodySuffix = " " + bodyPlaceholder();

        String nodeType = funcNode.getType();

        if (nodeType(TsxNodeType.ARROW_FUNCTION).equals(nodeType)) {
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
            signature = String.format(
                    "%s%s%s%s%s =>", cleanedExportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        } else { // Assumes "function_declaration", "method_definition" etc.
            signature = String.format(
                    "%s%sfunction %s%s%s", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        }
        return signature + bodySuffix; // Do not prepend indent here
    }

    private boolean isJsxNode(@Nullable TSNode node) {
        if (node == null) return false;
        String type = node.getType();
        return nodeType(TsxNodeType.JSX_ELEMENT).equals(type)
                || nodeType(TsxNodeType.JSX_SELF_CLOSING_ELEMENT).equals(type)
                || JSX_FRAGMENT.equals(type);
    }

    private boolean returnsJsxElement(TSNode funcNode, SourceContent sourceContent) {
        TSNode bodyNode =
                funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        if (bodyNode == null) {
            return false;
        }

        // Case 1: Arrow function with implicit return: () => <div />
        if (nodeType(TsxNodeType.ARROW_FUNCTION).equals(funcNode.getType())) {
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
            String jsxReturnQueryStr =
                    """
    (return_statement (jsx_element) @jsx_return)
    (return_statement (jsx_self_closing_element) @jsx_return)
    (return_statement (parenthesized_expression (jsx_element)) @jsx_return)
    (return_statement (parenthesized_expression (jsx_self_closing_element)) @jsx_return)
    """;

            try (TSQuery returnJsxQuery = new TSQuery(jsLanguage, jsxReturnQueryStr);
                    TSQueryCursor cursor = new TSQueryCursor()) {
                cursor.exec(returnJsxQuery, bodyNode, sourceContent.text());
                TSQueryMatch match = new TSQueryMatch();
                if (cursor.nextMatch(match)) {
                    return true; // Found a JSX return
                }
            }
        } catch (TSQueryException e) {
            // Log specific query exceptions, which usually indicate a problem with the query string itself.
            log.error("Invalid TSQuery for JSX return type inference: {}", e.getMessage(), e);
        }
        return false;
    }

    @Override
    protected List<String> getExtraFunctionComments(
            @Nullable TSNode bodyNode, SourceContent sourceContent, @Nullable CodeUnit functionCu) {
        if (bodyNode == null) {
            return List.of();
        }

        Set<String> mutatedIdentifiers = new HashSet<>();
        String mutationQueryStr =
                """
    (assignment_expression left: (identifier) @mutated.id)
    (assignment_expression left: (member_expression property: (property_identifier) @mutated.id))
    (assignment_expression left: (subscript_expression index: _ @mutated.id))
    (update_expression argument: (identifier) @mutated.id)
    (update_expression argument: (member_expression property: (property_identifier) @mutated.id))
    """;

        TSLanguage jsLanguage = getTSLanguage();
        try (TSQuery mutationQuery = new TSQuery(jsLanguage, mutationQueryStr);
                TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(mutationQuery, bodyNode, sourceContent.text());
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    String captureName = mutationQuery.getCaptureNameForId(capture.getIndex());
                    if ("mutated.id".equals(captureName)) {
                        TSNode node = capture.getNode();
                        mutatedIdentifiers.add(sourceContent.substringFrom(node));
                    }
                }
            }
        }

        if (!mutatedIdentifiers.isEmpty()) {
            List<String> sortedMutations = new ArrayList<>(mutatedIdentifiers);
            Collections.sort(sortedMutations);
            return List.of("// mutates: " + String.join(", ", sortedMutations));
        }

        return List.of();
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, SourceContent sourceContent) {

        TSNode parent = node.getParent();

        if (parent != null) {
            // Check if 'node' is a variable_declarator and its parent is lexical_declaration or variable_declaration
            // This is for field definitions like `const a = 1;` or `export let b = 2;`
            // where `node` is the `variable_declarator` (e.g., `a = 1`).
            if ((nodeType(TsxNodeType.LEXICAL_DECLARATION).equals(parent.getType())
                            || nodeType(TsxNodeType.VARIABLE_DECLARATION).equals(parent.getType()))
                    && nodeType(TsxNodeType.VARIABLE_DECLARATOR).equals(node.getType())) {
                // lexical_declaration or variable_declaration
                String keyword = "";
                // The first child of lexical/variable_declaration is the keyword (const, let, var)
                TSNode keywordNode = parent.getChild(0);
                if (keywordNode != null) {
                    keyword = sourceContent.substringFrom(keywordNode); // "const", "let", or "var"
                }

                String exportStr = "";
                TSNode exportStatementNode = parent.getParent(); // Parent of lexical/variable_declaration
                if (exportStatementNode != null
                        && nodeType(TsxNodeType.EXPORT_STATEMENT).equals(exportStatementNode.getType())) {
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
            if (nodeType(TsxNodeType.EXPORT_STATEMENT).equals(parent.getType())) {
                // This handles `export class Foo {}`, `export function bar() {}`
                return "export ";
            }

            // Case 2: node is the value of a variable declarator (e.g., an arrow_function or class_expression),
            // and the containing lexical_declaration or variable_declaration is exported.
            // e.g., `export const foo = () => {}` -> `node` is `arrow_function`, `parent` is `variable_declarator`.
            if (nodeType(TsxNodeType.VARIABLE_DECLARATOR).equals(parent.getType())) {
                TSNode lexicalOrVarDeclNode = parent.getParent();
                if (lexicalOrVarDeclNode != null
                        && (nodeType(TsxNodeType.LEXICAL_DECLARATION).equals(lexicalOrVarDeclNode.getType())
                                || nodeType(TsxNodeType.VARIABLE_DECLARATION).equals(lexicalOrVarDeclNode.getType()))) {
                    TSNode exportStatementNode = lexicalOrVarDeclNode.getParent();
                    if (exportStatementNode != null
                            && nodeType(TsxNodeType.EXPORT_STATEMENT).equals(exportStatementNode.getType())) {
                        // For `export const Foo = () => {}`, this returns "export "
                        return "export ";
                    }
                }
            }
        }
        return ""; // Default: no prefix
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        // The 'baseIndent' parameter is now "" when called from buildSignatureString.
        // Stored signature should be unindented.
        return exportPrefix + signatureText + " {"; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        // JavaScript package naming is directory-based, relative to the project root.
        // The definitionNode, rootNode, and sourceContent parameters are not used for JS package determination here.
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

    private boolean isLiteralType(@Nullable String type) {
        if (type == null) return false;
        return type.endsWith("literal")
                || nodeType(TsxNodeType.NUMBER).equals(type)
                || nodeType(TsxNodeType.STRING).equals(type)
                || nodeType(TsxNodeType.TEMPLATE_STRING).equals(type)
                || nodeType(TsxNodeType.TRUE).equals(type)
                || nodeType(TsxNodeType.FALSE).equals(type)
                || nodeType(TsxNodeType.NULL).equals(type)
                || nodeType(TsxNodeType.UNDEFINED).equals(type);
    }

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String simpleName,
            String baseIndent,
            ProjectFile file) {
        // JavaScript field signatures shouldn't have semicolons.
        var fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();

        if (nodeType(TsxNodeType.VARIABLE_DECLARATOR).equals(fieldNode.getType())) {
            TSNode valueNode = fieldNode.getChildByFieldName(FIELD_VALUE);
            if (valueNode != null && !isLiteralType(valueNode.getType())) {
                String valueText = sourceContent.substringFrom(valueNode).strip();
                int idx = fullSignature.lastIndexOf(valueText);
                if (idx != -1) {
                    String beforeValue = fullSignature.substring(0, idx).stripTrailing();
                    if (beforeValue.endsWith("=")) {
                        beforeValue = beforeValue
                                .substring(0, beforeValue.length() - 1)
                                .stripTrailing();
                    }
                    fullSignature = beforeValue;
                }
            }
        }

        return baseIndent + fullSignature;
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return JS_SYNTAX_PROFILE;
    }

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        super.extractImports(capturedNodesForMatch, sourceContent, localImportInfos);
    }

    /**
     * Extracts all identifiers (names and aliases) from an import statement string.
     */
    @Override
    public Set<String> extractIdentifiersFromImport(String importStatement) {
        Set<String> identifiers = new HashSet<>();
        TSParser parser = getTSParser();
        try {
            TSTree tree = parser.parseStringOrThrow(null, importStatement);
            TSNode rootNode = tree.getRootNode();
            if (rootNode == null) return identifiers;
            SourceContent sourceContent = SourceContent.of(importStatement);

            String queryStr =
                    """
                (import_clause (identifier) @import.id)
                (import_specifier name: (identifier) @import.id)
                (import_specifier alias: (identifier) @import.alias)
                (namespace_import (identifier) @import.alias)
                (variable_declarator
                  name: [
                    (identifier) @import.id
                    (object_pattern (shorthand_property_identifier_pattern) @import.id)
                  ]
                  value: (call_expression function: (identifier) @import.require_func))
                """;

            try (TSQuery query = new TSQuery(getTSLanguage(), queryStr);
                    TSQueryCursor cursor = new TSQueryCursor()) {
                cursor.exec(query, rootNode, sourceContent.text());
                TSQueryMatch match = new TSQueryMatch();

                while (cursor.nextMatch(match)) {
                    TSNode requireFunc = null;
                    TSNode importId = null;

                    for (TSQueryCapture capture : match.getCaptures()) {
                        String captureName = query.getCaptureNameForId(capture.getIndex());
                        if (captureName.equals("import.id")) {
                            importId = capture.getNode();
                        } else if (captureName.equals("import.require_func")) {
                            requireFunc = capture.getNode();
                        }
                    }

                    if (requireFunc != null
                            && !sourceContent.substringFrom(requireFunc).equals("require")) {
                        continue;
                    }

                    if (importId != null) {
                        identifiers.add(sourceContent.substringFrom(importId).strip());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse import statement: {}", importStatement, e);
        }
        return identifiers;
    }

    /**
     * Extracts identifiers and JSX tags from JavaScript source.
     * <p>
     * Trade-off: Precision. We capture standard identifiers and JSX-specific tags. While {@code identifier}
     * can over-match local variables, it is necessary in JS to find the source of functions and constants
     * imported via ES6 or CommonJS.
     */
    @Override
    public Set<String> extractTypeIdentifiers(String source) {
        Set<String> identifiers = new HashSet<>();
        TSParser parser = getTSParser();
        try (TSTree tree = parser.parseString(null, source)) {
            if (tree == null) {
                return identifiers;
            }
            TSNode rootNode = tree.getRootNode();
            if (rootNode == null) return identifiers;
            SourceContent sourceContent = SourceContent.of(source);

            try (TSQuery query = createQuery(QueryType.IDENTIFIERS)) {
                if (query != null) {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, rootNode, sourceContent.text());
                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                TSNode node = capture.getNode();
                                if (node != null) {
                                    identifiers.add(sourceContent.substringFrom(node));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract type identifiers from JavaScript source", e);
        }
        return identifiers;
    }
}
