package ai.brokk.analyzer;

import static ai.brokk.analyzer.php.Constants.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.cache.PhpAnalyzerCache;
import ai.brokk.project.ICoreProject;
import java.util.*;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.treesitter.*;

public final class PhpAnalyzer extends TreeSitterAnalyzer {
    // PHP_LANGUAGE field removed, createTSLanguage will provide new instances.

    private static final LanguageSyntaxProfile PHP_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DECLARATION, INTERFACE_DECLARATION, TRAIT_DECLARATION), // classLikeNodeTypes
            Set.of(FUNCTION_DEFINITION, METHOD_DECLARATION), // functionLikeNodeTypes
            Set.of(PROPERTY_DECLARATION, CONST_DECLARATION), // fieldLikeNodeTypes (capturing the whole declaration)
            Set.of(), // constructorNodeTypes are just `function.definition` so we need to check name
            Set.of(ATTRIBUTE_LIST), // decoratorNodeTypes (PHP attributes are grouped in attribute_list)
            IMPORT_DECLARATION,
            FIELD_NAME, // identifierFieldName
            FIELD_BODY, // bodyFieldName (applies to functions/methods, class body is declaration_list)
            FIELD_PARAMETERS, // parametersFieldName
            FIELD_RETURN_TYPE, // returnTypeFieldName (for return type declaration)
            "", // typeParametersFieldName (PHP doesn't have generics)
            Map.of( // captureConfiguration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.INTERFACE_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.TRAIT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE,
                    CaptureNames.ATTRIBUTE_DEFINITION,
                            SkeletonType.UNSUPPORTED // Attributes are handled by getPrecedingDecorators
                    ),
            "", // asyncKeywordNodeType (PHP has no async/await keywords for functions)
            Set.of(
                    VISIBILITY_MODIFIER,
                    STATIC_MODIFIER,
                    ABSTRACT_MODIFIER,
                    FINAL_MODIFIER,
                    READONLY_MODIFIER) // modifierNodeTypes
            );

    private static final String NAMESPACE_QUERY_STR = "(namespace_definition name: (namespace_name) @nsname)";

    public PhpAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public PhpAnalyzer(ICoreProject project, ProgressListener listener) {
        this(project, listener, new PhpAnalyzerCache());
    }

    private PhpAnalyzer(ICoreProject project, ProgressListener listener, PhpAnalyzerCache cache) {
        super(project, Languages.PHP, listener, cache);
    }

    private PhpAnalyzer(
            ICoreProject project, AnalyzerState state, ProgressListener listener, @Nullable PhpAnalyzerCache cache) {
        super(project, Languages.PHP, state, listener, cache);
    }

    public static PhpAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new PhpAnalyzer(project, state, listener, new PhpAnalyzerCache());
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        PhpAnalyzerCache phpCache = previousCache instanceof PhpAnalyzerCache p ? p : new PhpAnalyzerCache();
        return new PhpAnalyzer(getProject(), state, listener, phpCache);
    }

    @Override
    protected AnalyzerCache createFilteredCache(AnalyzerCache previous, Set<ProjectFile> changedFiles) {
        if (previous instanceof PhpAnalyzerCache phpCache) {
            return new PhpAnalyzerCache(phpCache, changedFiles);
        }
        return new PhpAnalyzerCache();
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterPhp();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/php/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/php/imports.scm");
            case IDENTIFIERS -> Optional.empty();
        };
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return PHP_SYNTAX_PROFILE;
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
            case CaptureNames.CLASS_DEFINITION, CaptureNames.INTERFACE_DEFINITION, CaptureNames.TRAIT_DEFINITION -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case CaptureNames.FUNCTION_DEFINITION -> { // Covers global functions and class methods
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case CaptureNames.FIELD_DEFINITION -> { // Covers class properties, class constants, and global constants
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
                if (!CaptureNames.ATTRIBUTE_DEFINITION.equals(captureName)
                        && !CaptureNames.NAMESPACE_DEFINITION.equals(captureName)
                        && // Main query's namespace.definition
                        !CAPTURE_NAMESPACE_NAME.equals(captureName)) { // Main query's namespace.name
                    log.debug(
                            "Ignoring capture in PhpAnalyzer: {} with name: {} and classChain: {}",
                            captureName,
                            simpleName,
                            classChain);
                }
                yield null; // Explicitly yield null
            }
        };
    }

    private String computeFilePackageName(ProjectFile file, TSNode rootNode, SourceContent sourceContent) {
        try (TSQuery query = new TSQuery(getTSLanguage(), NAMESPACE_QUERY_STR)) {
            return runNamespaceQuery(query, rootNode, sourceContent);
        } catch (Exception e) {
            log.error(
                    "Failed to compile namespace query for PhpAnalyzer in computeFilePackageName for file {}: {}",
                    file,
                    e.getMessage(),
                    e);
            return "";
        }
    }

    private String runNamespaceQuery(TSQuery query, TSNode rootNode, SourceContent sourceContent) {
        try (TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, rootNode, sourceContent.text());
            TSQueryMatch match = new TSQueryMatch();

            if (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    if (CAPTURE_NSNAME.equals(query.getCaptureNameForId(capture.getIndex()))) {
                        TSNode nameNode = capture.getNode();
                        if (nameNode != null) {
                            return sourceContent.substringFrom(nameNode).replace('\\', '.');
                        }
                    }
                }
            }
        }
        // Fallback to manual scan if query fails or no match, though query is preferred
        int i = 0;
        for (TSNode current : rootNode.getChildren()) {
            if (NAMESPACE_DEFINITION.equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName(FIELD_NAME);
                if (nameNode != null) {
                    return sourceContent.substringFrom(nameNode).replace('\\', '.');
                }
            }
            if (!PHP_TAG.equals(current.getType())
                    && !NAMESPACE_DEFINITION.equals(current.getType())
                    && !DECLARE_STATEMENT.equals(current.getType())
                    && i > 5) {
                break; // Stop searching after a few top-level elements
            }
            i++;
        }
        return ""; // No namespace found
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        AnalyzerCache currentCache = getCache();
        if (currentCache instanceof PhpAnalyzerCache phpCache) {
            return phpCache.fileScopedPackageNamesCache()
                    .get(file, f -> computeFilePackageName(f, rootNode, sourceContent));
        }
        return computeFilePackageName(file, rootNode, sourceContent);
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        if (cu.isClass()) { // CodeUnit.cls is used for class, interface, trait
            boolean isEmptyCuBody = childrenOf(cu).isEmpty();
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

    @Override
    public List<ExceptionHandlingSmell> findExceptionHandlingSmells(ProjectFile file, ExceptionSmellWeights weights) {
        checkStale("findExceptionHandlingSmells");
        ExceptionSmellWeights resolvedWeights = weights != null ? weights : ExceptionSmellWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file,
                            source -> detectExceptionHandlingSmells(file, root, source, resolvedWeights),
                            List.of());
                },
                List.of());
    }

    private static final Set<String> PHP_COMMENT_NODE_TYPES = Set.of(COMMENT);
    private static final Pattern PHP_LOG_ONLY_PATTERN = Pattern.compile(
            "(?i)(?:\\$this\\s*->\\s*(?:logger|log)|\\$(?:logger|log))\\s*->\\s*(?:error|warn|warning|info|debug|trace)\\b"
                    + "|\\bLog\\s*::\\s*(?:error|warn|warning|info|debug|trace)\\b"
                    + "|\\berror_log\\s*\\(");

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var findings = new ArrayList<SmellCandidate>();
        collectCatchSmells(file, root, sourceContent, weights, findings);
        return findings.stream()
                .sorted(EXCEPTION_SMELL_CANDIDATE_COMPARATOR)
                .map(SmellCandidate::smell)
                .toList();
    }

    private void collectCatchSmells(
            ProjectFile file,
            TSNode node,
            SourceContent sourceContent,
            ExceptionSmellWeights weights,
            List<SmellCandidate> out) {
        if (node == null) {
            return;
        }
        if (CATCH_CLAUSE.equals(node.getType())) {
            analyzeCatchClause(file, node, sourceContent, weights).ifPresent(out::add);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectCatchSmells(file, child, sourceContent, weights, out);
            }
        }
    }

    private Optional<SmellCandidate> analyzeCatchClause(
            ProjectFile file, TSNode catchClause, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode bodyNode = catchClause.getChildByFieldName(FIELD_BODY);
        if (bodyNode == null) {
            bodyNode = firstNamedChildOfType(catchClause, COMPOUND_STATEMENT);
        }
        if (bodyNode == null) {
            return Optional.empty();
        }

        int bodyStatements = countBodyStatements(bodyNode);
        boolean hasAnyComment = hasDescendantOfType(bodyNode, COMMENT);
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean throwPresent = hasDescendantOfType(bodyNode, THROW_STATEMENT);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !throwPresent;

        String catchType = extractCatchType(catchClause, sourceContent);
        List<String> normalizedTypes = normalizeCatchTypesForScoring(catchType);

        int score = 0;
        var reasons = new ArrayList<String>();
        if (normalizedTypes.contains("throwable")) {
            score += weights.genericThrowableWeight();
            reasons.add("generic-catch:Throwable");
        } else if (normalizedTypes.contains("exception")) {
            score += weights.genericExceptionWeight();
            reasons.add("generic-catch:Exception");
        } else if (normalizedTypes.contains("runtimeexception")) {
            score += weights.genericRuntimeExceptionWeight();
            reasons.add("generic-catch:RuntimeException");
        }
        if (emptyBody) {
            score += weights.emptyBodyWeight();
            reasons.add("empty-body");
        }
        if (commentOnlyBody) {
            score += weights.commentOnlyBodyWeight();
            reasons.add("comment-only-body");
        }
        if (smallBody) {
            score += weights.smallBodyWeight();
            reasons.add("small-body:" + bodyStatements);
        }
        if (logOnly) {
            score += weights.logOnlyWeight();
            reasons.add("log-only-body");
        }

        int creditStatements = Math.min(bodyStatements, Math.max(0, weights.meaningfulBodyStatementThreshold()));
        int bodyCredit = Math.max(0, weights.meaningfulBodyCreditPerStatement()) * creditStatements;
        if (bodyCredit > 0) {
            score -= bodyCredit;
            reasons.add("meaningful-body-credit:" + bodyCredit);
        }
        if (score <= 0) {
            return Optional.empty();
        }

        String enclosing = enclosingCodeUnit(
                        file,
                        catchClause.getStartPoint().getRow(),
                        catchClause.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        var smell = new ExceptionHandlingSmell(
                file,
                enclosing,
                catchType,
                score,
                bodyStatements,
                List.copyOf(reasons),
                compactCatchExcerpt(sourceContent.substringFrom(catchClause)));
        return Optional.of(new SmellCandidate(smell, catchClause.getStartByte()));
    }

    private static int countBodyStatements(TSNode bodyNode) {
        int statements = 0;
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode child = bodyNode.getNamedChild(i);
            if (child == null) {
                continue;
            }
            if (PHP_COMMENT_NODE_TYPES.contains(child.getType())) {
                continue;
            }
            statements++;
        }
        return statements;
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode statement = firstNonCommentNamedChild(bodyNode, PHP_COMMENT_NODE_TYPES);
        if (statement == null) {
            return false;
        }
        String text = sourceContent.substringFrom(statement).strip();
        if (text.isEmpty()) {
            return false;
        }
        return PHP_LOG_ONLY_PATTERN.matcher(text).find();
    }

    private static String extractCatchType(TSNode catchClause, SourceContent sourceContent) {
        TSNode typeNode = catchClause.getChildByFieldName(FIELD_TYPE);
        if (typeNode != null) {
            String type = sourceContent.substringFrom(typeNode).strip();
            if (!type.isEmpty()) {
                return type.replaceAll("\\s+", " ");
            }
        }

        for (int i = 0; i < catchClause.getNamedChildCount(); i++) {
            TSNode child = catchClause.getNamedChild(i);
            if (child == null) {
                continue;
            }
            String type = Objects.toString(child.getType(), "");
            if (COMPOUND_STATEMENT.equals(type) || VARIABLE_NAME.equals(type) || COMMENT.equals(type)) {
                continue;
            }
            String text = sourceContent.substringFrom(child).strip();
            if (!text.isEmpty()) {
                return text.replaceAll("\\s+", " ");
            }
        }

        return "<unknown>";
    }

    private static List<String> normalizeCatchTypesForScoring(String catchType) {
        if (catchType.isBlank() || "<unknown>".equals(catchType)) {
            return List.of();
        }
        return Arrays.stream(catchType.split("\\|"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith("\\") ? s.substring(1) : s)
                .map(s -> {
                    int lastSep = s.lastIndexOf('\\');
                    return lastSep >= 0 ? s.substring(lastSep + 1) : s;
                })
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static String compactCatchExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private static @Nullable TSNode firstNamedChildOfType(TSNode node, String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null && type.equals(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private String extractModifiers(TSNode methodNode, SourceContent sourceContent) {
        StringBuilder sb = new StringBuilder();
        for (TSNode child : methodNode.getChildren()) {
            String type = child.getType();

            if (PHP_SYNTAX_PROFILE.decoratorNodeTypes().contains(type)) { // This is an attribute
                sb.append(sourceContent.substringFrom(child)).append("\n");
            } else if (PHP_SYNTAX_PROFILE.modifierNodeTypes().contains(type)) { // This is a keyword modifier
                sb.append(sourceContent.substringFrom(child)).append(" ");
            } else if (FUNCTION_KEYWORD.equals(type)) { // Stop when the 'function' keyword token itself is encountered
                break;
            }
            // Other child types (e.g., comments, other anonymous tokens before 'function') are skipped.
        }
        return sb.toString();
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

        boolean isConstant = CONST_DECLARATION.equals(fieldNode.getType());
        boolean isProperty = PROPERTY_DECLARATION.equals(fieldNode.getType());

        if (!isConstant && !isProperty) {
            return super.formatFieldSignature(
                    fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
        }

        TSNode elementNode = null;
        if (isProperty) {
            for (TSNode child : fieldNode.getNamedChildren()) {
                if (PROPERTY_ELEMENT.equals(child.getType())) {
                    TSNode nameNode = findNameNodeRecursive(child);
                    if (nameNode != null) {
                        String nameText = sourceContent.substringFrom(nameNode).strip();
                        if (nameText.equals(simpleName) || nameText.equals("$" + simpleName)) {
                            elementNode = child;
                            simpleName = nameText; // keep the $ prefix
                            break;
                        }
                    }
                }
            }
        } else {
            for (TSNode child : fieldNode.getNamedChildren()) {
                if (CONST_ELEMENT.equals(child.getType())) {
                    TSNode nameNode = findNameNodeRecursive(child);
                    if (nameNode != null) {
                        String nameText = sourceContent.substringFrom(nameNode).strip();
                        if (nameText.equals(simpleName)) {
                            elementNode = child;
                            break;
                        }
                    }
                }
            }
        }

        if (elementNode == null) {
            return super.formatFieldSignature(
                    fieldNode, sourceContent, exportPrefix, signatureText, simpleName, baseIndent, file);
        }

        // Look for type information
        String typeText = "";
        TSNode typeNode = fieldNode.getChildByFieldName(FIELD_TYPE);
        if (typeNode != null) {
            typeText = sourceContent.substringFrom(typeNode).strip() + " ";
        }

        // Look for initializers
        TSNode valueNode = null;
        if (isProperty) {
            valueNode = elementNode.getChildByFieldName(FIELD_DEFAULT_VALUE);
            if (valueNode == null) {
                if (elementNode.getNamedChildCount() > 1) {
                    valueNode = elementNode.getNamedChild(elementNode.getNamedChildCount() - 1);
                }
            }
        } else {
            valueNode = elementNode.getChildByFieldName(FIELD_VALUE);
            if (valueNode == null) {
                if (elementNode.getNamedChildCount() > 1) {
                    valueNode = elementNode.getNamedChild(elementNode.getNamedChildCount() - 1);
                }
            }
        }

        if (valueNode != null
                && PROPERTY_INITIALIZER.equals(valueNode.getType())
                && valueNode.getNamedChildCount() > 0) {
            valueNode = valueNode.getNamedChild(0);
        }

        String initializerText = "";
        if (valueNode != null && isLiteralType(valueNode.getType())) {
            initializerText = " = " + sourceContent.substringFrom(valueNode).strip();
        }

        String modifiers = extractModifiers(fieldNode, sourceContent);

        String constPrefix = isConstant ? "const " : "";
        String trimmedExport = exportPrefix.stripTrailing();

        String fullModifiers = trimmedExport;
        if (!fullModifiers.isEmpty() && !modifiers.isEmpty()) {
            fullModifiers += " ";
        }
        fullModifiers += modifiers.stripTrailing();

        if (isConstant && (fullModifiers.endsWith(" const") || fullModifiers.equals("const"))) {
            constPrefix = "";
        }

        String fullSignature = fullModifiers;
        if (!fullSignature.isEmpty()) {
            fullSignature += " ";
        }
        String propertyPrefix = isProperty ? "$" : "";
        fullSignature += constPrefix + typeText + propertyPrefix + simpleName + initializerText + ";";

        return baseIndent + fullSignature.stripLeading();
    }

    private @Nullable TSNode findNameNodeRecursive(@Nullable TSNode node) {
        if (node == null) return null;
        if (NAME.equals(node.getType())) return node;
        for (TSNode child : node.getNamedChildren()) {
            TSNode found = findNameNodeRecursive(child);
            if (found != null) return found;
        }
        return null;
    }

    private boolean isLiteralType(@Nullable String type) {
        if (type == null) return false;
        return type.endsWith("_literal")
                || INTEGER.equals(type)
                || FLOAT.equals(type)
                || STRING.equals(type)
                || ENCAPSED_STRING.equals(type)
                || STRING_VALUE.equals(type)
                || BOOLEAN.equals(type)
                || BOOLEAN_LITERAL.equals(type)
                || NULL.equals(type)
                || NULL_LITERAL.equals(type);
    }

    @Override
    protected String renderClassHeader(
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        TSNode bodyNode = classNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        boolean isEmptyBody =
                (bodyNode == null || bodyNode.getNamedChildCount() == 0); // bodyNode.isNull() check removed
        String suffix = isEmptyBody ? " { }" : " {";

        return signatureText.stripTrailing() + suffix;
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
        // Attributes that are children of the funcNode (e.g., PHP attributes on methods)
        // are collected by extractModifiers.
        // exportPrefix and asyncPrefix are "" for PHP. indent is also "" at this stage from base.
        String modifiers = extractModifiers(funcNode, sourceContent);

        String ampersand = "";
        TSNode referenceModifierNode = null;
        // Iterate children to find reference_modifier, as its position can vary slightly.
        for (TSNode child : funcNode.getChildren()) {
            if (REFERENCE_MODIFIER.equals(child.getType())) {
                referenceModifierNode = child;
                break;
            }
        }

        if (referenceModifierNode != null) { // No need for !referenceModifierNode.isNull()
            ampersand = sourceContent.substringFrom(referenceModifierNode).trim();
        }

        String formattedReturnType = "";
        if (!returnTypeText.isEmpty()) {
            formattedReturnType = ": " + returnTypeText.strip();
        }

        String ampersandPart = ampersand.isEmpty() ? "" : ampersand;

        String mainSignaturePart = String.format(
                        "%sfunction %s%s%s%s", modifiers, ampersandPart, functionName, paramsText, formattedReturnType)
                .stripTrailing();

        TSNode bodyNode = funcNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        // If bodyNode is null or not a compound statement, it's an abstract/interface method.
        if (bodyNode != null && COMPOUND_STATEMENT.equals(bodyNode.getType())) {
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
    protected String getVisibilityPrefix(TSNode node, SourceContent sourceContent) {
        return "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // namespace.definition and namespace.name from the main query are ignored
        // as namespace processing is now handled by computeFilePackageName.
        // attribute.definition is handled by decorator logic in base class.
        return Set.of(CaptureNames.NAMESPACE_DEFINITION, CAPTURE_NAMESPACE_NAME, CaptureNames.ATTRIBUTE_DEFINITION);
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        var rootNode = tree.getRootNode();
        if (rootNode == null) return false;
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        cursor.exec(query, rootNode, sourceContent.text());
                        TSQueryMatch match = new TSQueryMatch();

                        while (cursor.nextMatch(match)) {
                            for (TSQueryCapture capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                if (!TEST_MARKER.equals(captureName)) {
                                    continue;
                                }

                                TSNode node = capture.getNode();
                                if (node == null) {
                                    continue;
                                }

                                String nodeType = node.getType();

                                // Name-based detection: @test_marker is captured on the function/method name node.
                                if (NAME.equals(nodeType)) {
                                    TSNode parent = node.getParent();
                                    if (parent == null) {
                                        continue;
                                    }

                                    String parentType = parent.getType();
                                    if (!FUNCTION_DEFINITION.equals(parentType)
                                            && !METHOD_DECLARATION.equals(parentType)) {
                                        continue;
                                    }

                                    String nameText = sourceContent.substringFrom(node);
                                    if (nameText.toLowerCase(Locale.ROOT).startsWith("test")) {
                                        return true;
                                    }
                                    continue;
                                }

                                // Docblock/Comment-based detection: @test_marker is also captured on comment nodes.
                                if (COMMENT.equals(nodeType)) {
                                    String commentText = sourceContent.substringFrom(node);
                                    if (!commentText.contains(TEST_TAG_AT_TEST)) {
                                        continue;
                                    }

                                    // Prefer AST adjacency: treat the comment as "belonging to" the next declaration
                                    // sibling.
                                    TSNode next = node.getNextSibling();
                                    while (next != null && isWhitespaceOnlyNode(next)) {
                                        next = next.getNextSibling();
                                    }

                                    if (next == null) {
                                        continue;
                                    }

                                    String nextType = next.getType();
                                    if (FUNCTION_DEFINITION.equals(nextType) || METHOD_DECLARATION.equals(nextType)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                    return false;
                },
                false);
    }

    private static final String TEST_ASSERTION_KIND_NO_ASSERTIONS = "no-assertions";
    private static final String TEST_ASSERTION_KIND_CONSTANT_TRUTH = "constant-truth";
    private static final String TEST_ASSERTION_KIND_CONSTANT_EQUALITY = "constant-equality";
    private static final String TEST_ASSERTION_KIND_SELF_COMPARISON = "self-comparison";
    private static final String TEST_ASSERTION_KIND_NULLNESS_ONLY = "nullness-only";
    private static final String TEST_ASSERTION_KIND_SHALLOW_ONLY = "shallow-assertions-only";
    private static final String TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL = "overspecified-literal";

    private static final Set<String> PHPUNIT_ASSERT_TRUE_FALSE = Set.of("asserttrue", "assertfalse");
    private static final Set<String> PHPUNIT_ASSERT_COMPARISONS =
            Set.of("assertequals", "assertsame", "assertnotequals", "assertnotsame");
    private static final Set<String> PHPUNIT_NULLNESS_ASSERTIONS = Set.of("assertnull", "assertnotnull");
    private static final Set<String> PHPUNIT_ASSERTION_EQUIVALENT_TERMINALS = Set.of("fail");

    @Override
    public List<TestAssertionSmell> findTestAssertionSmells(ProjectFile file, TestAssertionWeights weights) {
        checkStale("findTestAssertionSmells");
        if (!containsTests(file)) {
            return List.of();
        }
        TestAssertionWeights resolvedWeights = weights != null ? weights : TestAssertionWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file, source -> detectTestAssertionSmells(file, root, source, resolvedWeights), List.of());
                },
                List.of());
    }

    private List<TestAssertionSmell> detectTestAssertionSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, TestAssertionWeights weights) {
        var candidates = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(FUNCTION_DEFINITION, METHOD_DECLARATION), candidates);
        var findings = new ArrayList<TestSmellCandidate>();
        for (TSNode fn : candidates) {
            if (!isTestFunction(fn, sourceContent)) {
                continue;
            }
            analyzeTestFunction(file, fn, sourceContent, weights, findings);
        }
        return findings.stream()
                .sorted(TEST_SMELL_CANDIDATE_COMPARATOR)
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private boolean isTestFunction(TSNode functionNode, SourceContent sourceContent) {
        TSNode nameNode = functionNode.getChildByFieldName(NAME);
        if (nameNode != null) {
            String nameText = sourceContent.substringFrom(nameNode).strip();
            if (nameText.toLowerCase(Locale.ROOT).startsWith("test")) {
                return true;
            }
        }
        return findPrecedingComments(functionNode).stream()
                .anyMatch(comment -> sourceContent.substringFrom(comment).contains(TEST_TAG_AT_TEST));
    }

    private void analyzeTestFunction(
            ProjectFile file,
            TSNode functionNode,
            SourceContent sourceContent,
            TestAssertionWeights weights,
            List<TestSmellCandidate> out) {
        TSNode body = functionNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        if (body == null) {
            return;
        }

        List<TSNode> calls = collectCallLikeNodes(body);
        List<AssertionSignal> assertions = calls.stream()
                .map(call -> assertionSignal(call, sourceContent, weights))
                .flatMap(Optional::stream)
                .toList();

        String enclosing = enclosingCodeUnit(
                        file,
                        functionNode.getStartPoint().getRow(),
                        functionNode.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());

        int assertionCount = assertions.size();
        if (assertionCount == 0) {
            addTestSmellCandidate(
                    file,
                    enclosing,
                    TEST_ASSERTION_KIND_NO_ASSERTIONS,
                    weights.noAssertionWeight(),
                    0,
                    List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                    sourceContent.substringFrom(functionNode),
                    functionNode.getStartByte(),
                    out);
            return;
        }

        assertions.stream()
                .filter(signal -> signal.baseScore() > 0)
                .forEach(signal -> addTestSmellCandidate(
                        file,
                        enclosing,
                        signal.kind(),
                        signal.baseScore(),
                        assertionCount,
                        signal.reasons(),
                        signal.excerpt(),
                        signal.startByte(),
                        out));

        boolean allShallow = assertions.stream().allMatch(AssertionSignal::shallow);
        if (allShallow) {
            int score = weights.shallowAssertionOnlyWeight()
                    - testMeaningfulAssertionCredit(assertions, weights, AssertionSignal::meaningful);
            if (score > 0) {
                addTestSmellCandidate(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_SHALLOW_ONLY,
                        score,
                        assertionCount,
                        List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                        sourceContent.substringFrom(functionNode),
                        functionNode.getStartByte(),
                        out);
            }
        }
    }

    private record AssertionSignal(
            String kind,
            int baseScore,
            boolean shallow,
            boolean meaningful,
            int startByte,
            List<String> reasons,
            String excerpt) {}

    private Optional<AssertionSignal> assertionSignal(
            TSNode call, SourceContent sourceContent, TestAssertionWeights weights) {
        TSNode functionNode = call.getChildByFieldName(FIELD_FUNCTION);
        if (functionNode == null) {
            functionNode = call.getChildByFieldName(FIELD_NAME);
        }
        if (functionNode == null && call.getNamedChildCount() > 0) {
            functionNode = call.getNamedChild(0);
        }
        String terminalLower = terminalCallName(functionNode, sourceContent).toLowerCase(Locale.ROOT);
        if (terminalLower.isBlank()) {
            return Optional.empty();
        }

        boolean assertionEquivalent = terminalLower.startsWith("assert")
                || terminalLower.startsWith("expectexception")
                || PHPUNIT_ASSERTION_EQUIVALENT_TERMINALS.contains(terminalLower);
        if (!assertionEquivalent) {
            return Optional.empty();
        }

        String excerpt = sourceContent.substringFrom(call);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean shallow = false;
        boolean meaningful = true;
        String kind = "phpunit-assertion";

        List<TSNode> args = callArgumentNodes(call);

        if (PHPUNIT_ASSERT_TRUE_FALSE.contains(terminalLower) && !args.isEmpty()) {
            TSNode arg = args.getFirst();
            String argText = sourceContent.substringFrom(arg).strip().toLowerCase(Locale.ROOT);
            boolean constantTruth = terminalLower.equals("asserttrue") && "true".equals(argText);
            boolean constantFalse = terminalLower.equals("assertfalse") && "false".equals(argText);
            if (constantTruth || constantFalse) {
                score += weights.constantTruthWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_TRUTH);
                kind = TEST_ASSERTION_KIND_CONSTANT_TRUTH;
                meaningful = false;
            }
            if (isSelfComparison(arg, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }

        if (PHPUNIT_ASSERT_COMPARISONS.contains(terminalLower) && args.size() >= 2) {
            TSNode left = args.get(0);
            TSNode right = args.get(1);
            if (isConstantExpression(left, sourceContent) && isConstantExpression(right, sourceContent)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if (sameExpression(left, right, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }

        if (PHPUNIT_NULLNESS_ASSERTIONS.contains(terminalLower) && args.size() <= 2) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            shallow = true;
            meaningful = false;
        }

        if (containsOverspecifiedLiteral(args, sourceContent, weights)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
        }

        return Optional.of(new AssertionSignal(
                kind, score, shallow, meaningful, call.getStartByte(), List.copyOf(reasons), excerpt));
    }

    private static List<TSNode> collectCallLikeNodes(TSNode root) {
        var out = new ArrayList<TSNode>();
        try (var cursor = new TSTreeCursor(root)) {
            while (true) {
                TSNode current = cursor.currentNode();
                if (current == null) {
                    return out;
                }
                String type = Objects.toString(current.getType(), "");
                if (type.endsWith(CALL_EXPRESSION) || CALL_EXPRESSION.equals(type)) {
                    out.add(current);
                }
                if (!gotoNextDepthFirst(cursor, true)) {
                    return out;
                }
            }
        }
    }

    private static String terminalCallName(@Nullable TSNode functionNode, SourceContent sourceContent) {
        if (functionNode == null) {
            return "";
        }

        if (NAME.equals(functionNode.getType())) {
            return sourceContent.substringFrom(functionNode).strip();
        }

        TSNode nameNode = functionNode.getChildByFieldName(FIELD_NAME);
        if (nameNode != null) {
            return sourceContent.substringFrom(nameNode).strip();
        }

        TSNode memberNode = functionNode.getChildByFieldName(FIELD_MEMBER);
        if (memberNode != null) {
            return sourceContent.substringFrom(memberNode).strip();
        }

        TSNode propertyNode = functionNode.getChildByFieldName(FIELD_PROPERTY);
        if (propertyNode != null) {
            return sourceContent.substringFrom(propertyNode).strip();
        }

        return "";
    }

    private static List<TSNode> callArgumentNodes(TSNode call) {
        TSNode args = call.getChildByFieldName(FIELD_ARGUMENTS);
        if (args == null) {
            for (int i = 0; i < call.getNamedChildCount(); i++) {
                TSNode child = call.getNamedChild(i);
                if (child != null && ARGUMENTS.equals(child.getType())) {
                    args = child;
                    break;
                }
            }
        }
        if (args == null) {
            return List.of();
        }
        var out = new ArrayList<TSNode>();
        for (int i = 0; i < args.getNamedChildCount(); i++) {
            TSNode child = args.getNamedChild(i);
            if (child != null) {
                if (ARGUMENT.equals(child.getType()) && child.getNamedChildCount() == 1) {
                    TSNode inner = child.getNamedChild(0);
                    if (inner != null) {
                        out.add(inner);
                        continue;
                    }
                }
                out.add(child);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isSelfComparison(TSNode node, SourceContent sourceContent) {
        String type = Objects.toString(node.getType(), "");
        if (!type.endsWith(BINARY_EXPRESSION)) {
            return false;
        }
        TSNode left = node.getChildByFieldName(FIELD_LEFT);
        TSNode right = node.getChildByFieldName(FIELD_RIGHT);
        if ((left == null || right == null) && node.getNamedChildCount() >= 2) {
            left = node.getNamedChild(0);
            right = node.getNamedChild(1);
        }
        return left != null && right != null && sameExpression(left, right, sourceContent);
    }

    private static boolean sameExpression(TSNode left, TSNode right, SourceContent sourceContent) {
        return sourceContent
                .substringFrom(left)
                .strip()
                .equals(sourceContent.substringFrom(right).strip());
    }

    private static final Set<String> CONSTANT_LITERAL_TYPES =
            Set.of(INTEGER, FLOAT, STRING, ENCAPSED_STRING, BOOLEAN, BOOLEAN_LITERAL, NULL, NULL_LITERAL);

    private static boolean isConstantExpression(TSNode node, SourceContent sourceContent) {
        if (CONSTANT_LITERAL_TYPES.contains(node.getType())) {
            return true;
        }
        String text = sourceContent.substringFrom(node).strip().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "false".equals(text) || "null".equals(text);
    }

    private static boolean containsOverspecifiedLiteral(
            List<TSNode> args, SourceContent sourceContent, TestAssertionWeights weights) {
        return args.stream().anyMatch(arg -> {
            String type = Objects.toString(arg.getType(), "");
            if (!STRING.equals(type) && !ENCAPSED_STRING.equals(type)) {
                return false;
            }
            return sourceContent.substringFrom(arg).length() >= weights.largeLiteralLengthThreshold();
        });
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForPhp(reference);
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return "__construct".equals(candidate.identifier());
    }
}
