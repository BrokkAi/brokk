package ai.brokk.analyzer;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.IProject;
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

public class JavascriptAnalyzer extends JsTsAnalyzer {
    private static final LanguageSyntaxProfile JS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DECLARATION, CLASS_EXPRESSION, CLASS),
            Set.of(FUNCTION_DECLARATION, ARROW_FUNCTION, METHOD_DEFINITION, FUNCTION_EXPRESSION),
            Set.of(VARIABLE_DECLARATOR),
            Set.of(), // JS standard decorators not captured as simple preceding nodes by current query.
            Set.of(),
            CaptureNames.IMPORT_DECLARATION,
            "name", // identifierFieldName
            "body", // bodyFieldName
            "parameters", // parametersFieldName
            "", // returnTypeFieldName (JS doesn't have a standard named child for return type)
            "", // typeParametersFieldName (JS doesn't have type parameters)
            Map.of(
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.ARROW_FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE),
            "async", // asyncKeywordNodeType
            Set.of() // modifierNodeTypes
            );

    public JavascriptAnalyzer(IProject project) {
        this(project, ProgressListener.NOOP);
    }

    public JavascriptAnalyzer(IProject project, ProgressListener listener) {
        super(project, Languages.JAVASCRIPT, listener);
    }

    private JavascriptAnalyzer(
            IProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.JAVASCRIPT, state, listener, cache);
    }

    public static JavascriptAnalyzer fromState(IProject project, AnalyzerState state, ProgressListener listener) {
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
            case CaptureNames.FIELD_DEFINITION -> { // For class fields or top-level variables
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
        if ("export_statement".equals(definitionNode.getType())) {
            TSNode declarationInExport = definitionNode.getChildByFieldName("declaration");
            if (declarationInExport != null && !declarationInExport.isNull()) {
                nodeForSignature = declarationInExport;
                nodeForContent = declarationInExport;
            }
        }

        // 2. Unwrap variable declaration to specific declarator for content/body extraction
        if (refined == SkeletonType.FIELD_LIKE || refined == SkeletonType.FUNCTION_LIKE) {
            String nodeType = nodeForContent.getType();
            if ("lexical_declaration".equals(nodeType) || "variable_declaration".equals(nodeType)) {
                // Find the variable_declarator child that matches the simpleName
                for (int i = 0; i < nodeForContent.getChildCount(); i++) {
                    TSNode child = nodeForContent.getChild(i);
                    if ("variable_declarator".equals(child.getType())) {
                        TSNode nameNode = child.getChildByFieldName(
                                getLanguageSyntaxProfile().identifierFieldName());
                        if (nameNode != null
                                && !nameNode.isNull()
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
            if (returnsJsxElement(funcNode)) {
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
            signature = String.format(
                    "%s%s%s%s%s =>", cleanedExportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        } else { // Assumes "function_declaration", "method_definition" etc.
            signature = String.format(
                    "%s%sfunction %s%s%s", exportPrefix, asyncPrefix, functionName, paramsText, tsReturnTypeSuffix);
        }
        return signature + bodySuffix; // Do not prepend indent here
    }

    private boolean isJsxNode(TSNode node) {
        if (node.isNull()) return false;
        String type = node.getType();
        return "jsx_element".equals(type) || "jsx_self_closing_element".equals(type) || "jsx_fragment".equals(type);
    }

    private boolean returnsJsxElement(TSNode funcNode) {
        TSNode bodyNode =
                funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
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
            String jsxReturnQueryStr =
                    """
    (return_statement (jsx_element) @jsx_return)
    (return_statement (jsx_self_closing_element) @jsx_return)
    (return_statement (parenthesized_expression (jsx_element)) @jsx_return)
    (return_statement (parenthesized_expression (jsx_self_closing_element)) @jsx_return)
    """;

            try (TSQuery returnJsxQuery = new TSQuery(jsLanguage, jsxReturnQueryStr);
                    TSQueryCursor cursor = new TSQueryCursor()) {
                cursor.exec(returnJsxQuery, bodyNode);
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
            TSNode bodyNode, SourceContent sourceContent, @Nullable CodeUnit functionCu) {
        if (bodyNode.isNull()) {
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
            cursor.exec(mutationQuery, bodyNode);
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    String captureName = mutationQuery.getCaptureNameForId(capture.getIndex());
                    if ("mutated.id".equals(captureName)) {
                        TSNode node = capture.getNode();
                        mutatedIdentifiers.add(
                                sourceContent.substringFromBytes(node.getStartByte(), node.getEndByte()));
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

        if (parent != null && !parent.isNull()) {
            // Check if 'node' is a variable_declarator and its parent is lexical_declaration or variable_declaration
            // This is for field definitions like `const a = 1;` or `export let b = 2;`
            // where `node` is the `variable_declarator` (e.g., `a = 1`).
            if (("lexical_declaration".equals(parent.getType()) || "variable_declaration".equals(parent.getType()))
                    && node.getType().equals("variable_declarator")) {
                TSNode declarationNode = parent; // lexical_declaration or variable_declaration
                String keyword = "";
                // The first child of lexical/variable_declaration is the keyword (const, let, var)
                TSNode keywordNode = declarationNode.getChild(0);
                if (keywordNode != null && !keywordNode.isNull()) {
                    keyword = sourceContent.substringFromBytes(
                            keywordNode.getStartByte(), keywordNode.getEndByte()); // "const", "let", or "var"
                }

                String exportStr = "";
                TSNode exportStatementNode = declarationNode.getParent(); // Parent of lexical/variable_declaration
                if (exportStatementNode != null
                        && !exportStatementNode.isNull()
                        && "export_statement".equals(exportStatementNode.getType())) {
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
                if (lexicalOrVarDeclNode != null
                        && !lexicalOrVarDeclNode.isNull()
                        && ("lexical_declaration".equals(lexicalOrVarDeclNode.getType())
                                || "variable_declaration".equals(lexicalOrVarDeclNode.getType()))) {
                    TSNode exportStatementNode = lexicalOrVarDeclNode.getParent();
                    if (exportStatementNode != null
                            && !exportStatementNode.isNull()
                            && "export_statement".equals(exportStatementNode.getType())) {
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
        // If fieldNode is a variable_declarator, we want to render just that declarator
        // prefixed by the export/declaration keyword found in exportPrefix.
        if (VARIABLE_DECLARATOR.equals(fieldNode.getType())) {
            return baseIndent + (exportPrefix.stripTrailing() + " " + sourceContent.substringFrom(fieldNode)).strip();
        }
        var fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();
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

        // Enhance the last added ImportInfo with JS-specific identifier/alias extraction
        TSNode importNode = capturedNodesForMatch.get(getLanguageSyntaxProfile().importNodeType());
        if (importNode != null && !importNode.isNull() && !localImportInfos.isEmpty()) {
            ImportInfo last = localImportInfos.getLast();
            // Verify this is the info for the node we just processed via super
            if (last.rawSnippet().equals(sourceContent.substringFrom(importNode))) {
                List<String> identifiers = new ArrayList<>();
                List<String> aliases = new ArrayList<>();
                extractNamedImportIdentifiers(importNode, sourceContent, identifiers, aliases);

                if (!identifiers.isEmpty() || !aliases.isEmpty()) {
                    String firstId = identifiers.isEmpty() ? null : identifiers.getFirst();
                    String firstAlias = aliases.isEmpty() ? null : aliases.getFirst();
                    localImportInfos.set(
                            localImportInfos.size() - 1,
                            new ImportInfo(last.rawSnippet(), last.isWildcard(), firstId, firstAlias));
                }
            }
        }
    }

    /**
     * Extracts identifiers and aliases from an import statement into the provided lists.
     */
    private void extractNamedImportIdentifiers(
            TSNode importNode, SourceContent sourceContent, List<String> identifiers, List<String> aliases) {
        // Query for:
        // 1. Default imports: import Foo from ...
        // 2. Named imports: import { Bar } from ...
        // 3. Aliased imports: import { Baz as Quux } from ...
        // 4. Namespace imports: import * as Telemetry from ...
        String queryStr =
                """
            (import_clause (identifier) @import.id)
            (import_specifier name: (identifier) @import.id)
            (import_specifier alias: (identifier) @import.alias)
            (namespace_import (identifier) @import.alias)
            """;

        try (TSQuery query = new TSQuery(getTSLanguage(), queryStr);
                TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, importNode);
            TSQueryMatch match = new TSQueryMatch();

            while (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    String captureName = query.getCaptureNameForId(capture.getIndex());
                    TSNode node = capture.getNode();
                    String text = sourceContent.substringFrom(node);

                    if ("import.id".equals(captureName)) {
                        identifiers.add(text);
                    } else if ("import.alias".equals(captureName)) {
                        aliases.add(text);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse named imports in JS", e);
        }
    }

    /**
     * Extracts all identifiers (names and aliases) from an import statement string.
     */
    @Override
    public Set<String> extractIdentifiersFromImport(String importStatement) {
        Set<String> identifiers = new HashSet<>();
        TSParser parser = getTSParser();
        try {
            SourceContent sourceContent = SourceContent.of(importStatement);
            TSTree tree = parser.parseString(null, importStatement);
            TSNode rootNode = tree.getRootNode();

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
                cursor.exec(query, rootNode);
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
            if (tree == null || tree.getRootNode().isNull()) {
                return identifiers;
            }
            SourceContent sourceContent = SourceContent.of(source);
            TSNode rootNode = tree.getRootNode();

            try (TSQuery query = createQuery(QueryType.IDENTIFIERS)) {
                if (query != null) {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, rootNode);
                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                TSNode node = capture.getNode();
                                if (node != null && !node.isNull()) {
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
