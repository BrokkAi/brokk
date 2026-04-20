package ai.brokk.analyzer;

import static ai.brokk.analyzer.csharp.CSharpTreeSitterNodeTypes.*;
import static ai.brokk.analyzer.csharp.Constants.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.csharp.CSharpTreeSitterNodeTypes;
import ai.brokk.project.ICoreProject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.CSharpNodeType;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCSharp;

/**
 * C# analyzer using Tree-sitter. Responsible for producing CodeUnits and skeletons for C# sources.
 */
public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    static final Logger log = LoggerFactory.getLogger(CSharpAnalyzer.class);

    private static final Set<String> CSHARP_COMMENT_NODE_TYPES = Set.of(LINE_COMMENT, BLOCK_COMMENT, COMMENT);
    private static final Set<String> LOG_RECEIVER_NAMES = Set.of("console", "trace", "debug", "logger", "log");
    private static final Set<String> LOG_METHOD_NAMES = Set.of(
            "writeline",
            "write",
            "print",
            "printf",
            "log",
            "logtrace",
            "logdebug",
            "loginformation",
            "logwarning",
            "logerror",
            "logcritical");
    private static final LanguageSyntaxProfile CS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(
                    CLASS_DECLARATION,
                    INTERFACE_DECLARATION,
                    STRUCT_DECLARATION,
                    RECORD_DECLARATION,
                    RECORD_STRUCT_DECLARATION),
            Set.of(METHOD_DECLARATION, CONSTRUCTOR_DECLARATION, LOCAL_FUNCTION_STATEMENT),
            Set.of(FIELD_DECLARATION, PROPERTY_DECLARATION, EVENT_FIELD_DECLARATION),
            Set.of(CaptureNames.CONSTRUCTOR_DEFINITION),
            Set.of("attribute_list"),
            IMPORT_DECLARATION,
            "name",
            "body",
            "parameters",
            "type",
            "type_parameter_list", // typeParametersFieldName (C# generics)
            Map.of(
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.INTERFACE_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.STRUCT_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.RECORD_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.METHOD_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.CONSTRUCTOR_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE),
            "",
            Set.of());

    public CSharpAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public CSharpAnalyzer(ICoreProject project, ProgressListener listener) {
        super(project, Languages.C_SHARP, listener);
        log.debug("CSharpAnalyzer: Constructor called for project: {}", project);
    }

    private CSharpAnalyzer(
            ICoreProject project,
            AnalyzerState prebuiltState,
            ProgressListener listener,
            @Nullable AnalyzerCache cache) {
        super(project, Languages.C_SHARP, prebuiltState, listener, cache);
    }

    public static CSharpAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new CSharpAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new CSharpAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterCSharp();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/c_sharp/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/c_sharp/imports.scm");
            case IDENTIFIERS -> Optional.empty();
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
        CodeUnit result =
                switch (captureName) {
                    case CaptureNames.CLASS_DEFINITION,
                            CaptureNames.INTERFACE_DEFINITION,
                            CaptureNames.STRUCT_DEFINITION,
                            CaptureNames.RECORD_DEFINITION -> {
                        String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                        yield CodeUnit.cls(file, packageName, finalShortName);
                    }
                    case CaptureNames.FUNCTION_DEFINITION,
                            CaptureNames.METHOD_DEFINITION,
                            CaptureNames.CONSTRUCTOR_DEFINITION -> {
                        String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                        yield CodeUnit.fn(file, packageName, finalShortName);
                    }
                    case CaptureNames.FIELD_DEFINITION -> {
                        String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                        yield CodeUnit.field(file, packageName, finalShortName);
                    }
                    default -> {
                        log.warn(
                                "Unhandled capture name in CSharpAnalyzer.createCodeUnit: '{}' for simple name '{}', "
                                        + "package '{}', classChain '{}' in file {}. Returning null.",
                                captureName,
                                simpleName,
                                packageName,
                                classChain,
                                file);
                        yield null;
                    }
                };
        log.trace("CSharpAnalyzer.createCodeUnit: returning {}", result);
        return result;
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // C# query explicitly captures attributes/annotations to ignore them.
        // We include 'attribute_list' and 'annotation.definition' to ensure they don't leak into skeletons.
        var ignored = Set.of("annotation.definition", "attribute_list", "test_attr");
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
            SourceContent sourceContent,
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

        if (body != null) {
            int startByte = funcNode.getStartByte();
            int endByte = body.getStartByte();
            signature = sourceContent.substringFromBytes(startByte, endByte).stripTrailing();
        } else {
            TSNode paramsNode = funcNode.getChildByFieldName("parameters");
            if (paramsNode != null) {
                int startByte = funcNode.getStartByte();
                int endByte = paramsNode.getEndByte();
                signature = sourceContent.substringFromBytes(startByte, endByte).stripTrailing();
            } else {
                signature = sourceContent
                        .substringFrom(funcNode)
                        .lines()
                        .findFirst()
                        .orElse("")
                        .stripTrailing();
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
            TSNode classNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String baseIndent) {
        return signatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        // C# namespaces are determined by traversing up from the definition node
        // to find enclosing namespace_declaration nodes.
        // The 'file' parameter is not used here as namespace is derived from AST content.
        List<String> namespaceParts = new ArrayList<>();
        TSNode current = definitionNode.getParent();

        while (current != null && !current.equals(rootNode)) {
            if (NAMESPACE_DECLARATION.equals(current.getType())) {
                // Find the identifier or qualified_name child as the name
                for (TSNode child : current.getChildren()) {
                    String type = child.getType();
                    if ("identifier".equals(type) || "qualified_name".equals(type)) {
                        String nsPart = sourceContent.substringFrom(child);
                        namespaceParts.add(nsPart);
                        break;
                    }
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
        var methods = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(nodeType(CSharpNodeType.METHOD_DECLARATION)), methods);
        var findings = new ArrayList<TestSmellCandidate>();
        for (TSNode method : methods) {
            if (isTestMethod(method, sourceContent)) {
                analyzeTestMethod(file, method, sourceContent, weights, findings);
            }
        }
        return findings.stream()
                .sorted(TEST_SMELL_CANDIDATE_COMPARATOR)
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private void analyzeTestMethod(
            ProjectFile file,
            TSNode method,
            SourceContent sourceContent,
            TestAssertionWeights weights,
            List<TestSmellCandidate> out) {
        TSNode body = method.getChildByFieldName(FIELD_BODY);
        if (body == null) {
            body = firstNamedChildOfType(method, nodeType(CSharpNodeType.BLOCK));
        }
        if (body == null) {
            return;
        }

        var invocations = new ArrayList<TSNode>();
        collectNodesByType(body, Set.of(nodeType(CSharpNodeType.INVOCATION_EXPRESSION)), invocations);
        List<AssertionSignal> assertions = invocations.stream()
                .map(call -> classifyAssertionCall(call, sourceContent, weights))
                .flatMap(Optional::stream)
                .toList();

        String enclosing = enclosingCodeUnit(
                        file,
                        method.getStartPoint().getRow(),
                        method.getEndPoint().getRow())
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
                    sourceContent.substringFrom(method),
                    method.getStartByte(),
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
                        sourceContent.substringFrom(method),
                        method.getStartByte(),
                        out);
            }
        }
    }

    private Optional<AssertionSignal> classifyAssertionCall(
            TSNode call, SourceContent sourceContent, TestAssertionWeights weights) {
        TSNode function = call.getChildByFieldName(FIELD_FUNCTION);
        if (function == null) {
            function = call.getChildByFieldName(FIELD_EXPRESSION);
        }
        if (function == null) {
            function = firstNamedChildOfType(call, nodeType(CSharpNodeType.MEMBER_ACCESS_EXPRESSION));
        }
        if (function == null) {
            function = firstNamedChildOfType(call, nodeType(CSharpNodeType.IDENTIFIER));
        }
        if (function == null) {
            return Optional.empty();
        }
        String methodName = invocationMethodName(function, sourceContent);
        if (methodName.isBlank()) {
            return Optional.empty();
        }
        if (methodName.equals("throws") || methodName.equals("throw")) {
            return Optional.of(new AssertionSignal(
                    TEST_ASSERTION_KIND_CSHARP,
                    0,
                    false,
                    true,
                    call.getStartByte(),
                    List.of(),
                    sourceContent.substringFrom(call)));
        }
        if (!CSHARP_ASSERTION_METHOD_NAMES.contains(methodName)) {
            return Optional.empty();
        }

        List<TSNode> args = argumentNodes(call);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean shallow = CSHARP_SHALLOW_ASSERTION_METHOD_NAMES.contains(methodName);
        boolean meaningful = !shallow;
        String kind = TEST_ASSERTION_KIND_CSHARP;
        if (("true".equals(methodName)
                        || "false".equals(methodName)
                        || "istrue".equals(methodName)
                        || "isfalse".equals(methodName))
                && !args.isEmpty()) {
            TSNode arg = args.getFirst();
            boolean constantTruth = (("true".equals(methodName) || "istrue".equals(methodName))
                            && isBooleanTrueLiteral(arg, sourceContent))
                    || (("false".equals(methodName) || "isfalse".equals(methodName))
                            && isBooleanFalseLiteral(arg, sourceContent));
            if (constantTruth) {
                score += weights.constantTruthWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_TRUTH);
                kind = TEST_ASSERTION_KIND_CONSTANT_TRUTH;
                meaningful = false;
            }
        }
        if (("equal".equals(methodName)
                        || "equals".equals(methodName)
                        || "same".equals(methodName)
                        || "areequal".equals(methodName))
                && args.size() >= 2) {
            TSNode expected = args.get(0);
            TSNode actual = args.get(1);
            if (isConstantExpression(expected, sourceContent) && isConstantExpression(actual, sourceContent)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if (sameExpression(expected, actual, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }
        if (shallow) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            meaningful = false;
        }
        if (containsOverspecifiedLiteral(args, sourceContent, weights)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
        }
        return Optional.of(new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                call.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(call)));
    }

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var catches = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CATCH_CLAUSE), catches);
        var findings = new ArrayList<SmellCandidate>();
        for (TSNode catchNode : catches) {
            analyzeCatchClause(file, catchNode, sourceContent, weights).ifPresent(findings::add);
        }
        return findings.stream()
                .sorted(EXCEPTION_SMELL_CANDIDATE_COMPARATOR)
                .map(SmellCandidate::smell)
                .toList();
    }

    private Optional<SmellCandidate> analyzeCatchClause(
            ProjectFile file, TSNode catchNode, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode bodyNode = catchNode.getChildByFieldName("body");
        if (bodyNode == null) {
            bodyNode = catchNode.getNamedChildren().stream()
                    .filter(child -> BLOCK.equals(child.getType()))
                    .findFirst()
                    .orElse(null);
        }
        if (bodyNode == null) {
            return Optional.empty();
        }

        String catchType = extractCatchType(catchNode, sourceContent);
        if (catchType.isEmpty()) {
            return Optional.empty();
        }

        int bodyStatements = countBodyStatements(bodyNode);
        boolean hasAnyComment = hasAnyComment(bodyNode);
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean throwPresent =
                hasDescendantOfType(bodyNode, THROW_STATEMENT) || hasDescendantOfType(bodyNode, THROW_EXPRESSION);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !throwPresent;

        int score = 0;
        var reasons = new ArrayList<String>();
        String normalizedCatchType = normalizeTypeNameForGenericCheck(catchType);
        if (isGenericExceptionType(normalizedCatchType)) {
            score += weights.genericExceptionWeight();
            reasons.add("generic-catch:Exception");
        } else if (isGenericThrowableType(normalizedCatchType)) {
            score += weights.genericThrowableWeight();
            reasons.add("generic-catch:Throwable");
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
                        catchNode.getStartPoint().getRow(),
                        catchNode.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        String excerpt = compactCatchExcerpt(sourceContent.substringFrom(catchNode));
        var smell = new ExceptionHandlingSmell(
                file, enclosing, catchType, score, bodyStatements, List.copyOf(reasons), excerpt);
        return Optional.of(new SmellCandidate(smell, catchNode.getStartByte()));
    }

    private static String extractCatchType(TSNode catchNode, SourceContent sourceContent) {
        var decl = findFirstNamedDescendant(catchNode, CATCH_DECLARATION);
        if (decl == null) {
            // C# catch-all: `catch { ... }`
            return "Exception";
        }
        TSNode typeNode = decl.getChildByFieldName("type");
        if (typeNode != null) {
            String typeText = sourceContent.substringFrom(typeNode).strip();
            if (!typeText.isEmpty()) {
                return typeText;
            }
        }
        TSNode fallback = firstNamedChildOfType(decl, Set.of(QUALIFIED_NAME, GENERIC_NAME, IDENTIFIER_NAME));
        if (fallback != null) {
            String typeText = sourceContent.substringFrom(fallback).strip();
            if (!typeText.isEmpty()) {
                return typeText;
            }
        }
        return "Exception";
    }

    private static int countBodyStatements(TSNode bodyNode) {
        int statements = 0;
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode child = bodyNode.getNamedChild(i);
            if (child == null) {
                continue;
            }
            if (CSHARP_COMMENT_NODE_TYPES.contains(child.getType())) {
                continue;
            }
            statements++;
        }
        return statements;
    }

    private static boolean hasAnyComment(TSNode bodyNode) {
        var comments = new ArrayList<TSNode>();
        collectNodesByType(bodyNode, CSHARP_COMMENT_NODE_TYPES, comments);
        return !comments.isEmpty();
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode statement = firstNonCommentNamedChild(bodyNode, CSHARP_COMMENT_NODE_TYPES);
        if (statement == null || !EXPRESSION_STATEMENT.equals(statement.getType())) {
            return false;
        }
        TSNode invocation = findFirstNamedDescendant(statement, INVOCATION_EXPRESSION);
        if (invocation == null) {
            return false;
        }

        TSNode expression = invocation.getChildByFieldName("expression");
        if (expression == null) {
            expression = invocation.getChildByFieldName("function");
        }
        if (expression == null) {
            return false;
        }

        String receiverText = "";
        String methodName = "";
        if (MEMBER_ACCESS_EXPRESSION.equals(expression.getType())) {
            TSNode receiverNode = expression.getChildByFieldName("expression");
            TSNode nameNode = expression.getChildByFieldName("name");
            if (receiverNode != null) {
                receiverText = sourceContent.substringFrom(receiverNode).strip().toLowerCase(Locale.ROOT);
            }
            if (nameNode != null) {
                methodName = sourceContent.substringFrom(nameNode).strip().toLowerCase(Locale.ROOT);
            }
        } else {
            methodName = sourceContent.substringFrom(expression).strip().toLowerCase(Locale.ROOT);
        }

        String receiverName = lastDotSegment(receiverText);
        if (LOG_RECEIVER_NAMES.contains(receiverName)) {
            return true;
        }
        return LOG_METHOD_NAMES.contains(methodName);
    }

    private static @Nullable TSNode firstNamedChildOfType(TSNode node, Set<String> candidateTypes) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child == null) {
                continue;
            }
            if (candidateTypes.contains(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private static String lastDotSegment(String text) {
        String cleaned = text.strip();
        if (cleaned.isEmpty()) {
            return "";
        }
        int lastDot = cleaned.lastIndexOf('.');
        return (lastDot >= 0 ? cleaned.substring(lastDot + 1) : cleaned).strip();
    }

    private static String normalizeTypeNameForGenericCheck(String typeText) {
        String cleaned = typeText.strip().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("global::")) {
            cleaned = cleaned.substring("global::".length());
        }
        return cleaned;
    }

    private static boolean isGenericExceptionType(String normalizedTypeName) {
        return "exception".equals(normalizedTypeName) || "system.exception".equals(normalizedTypeName);
    }

    private static boolean isGenericThrowableType(String normalizedTypeName) {
        return "throwable".equals(normalizedTypeName) || "system.throwable".equals(normalizedTypeName);
    }

    private static String compactCatchExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private static boolean isTestMethod(TSNode method, SourceContent sourceContent) {
        for (TSNode child : method.getNamedChildren()) {
            if (!nodeType(CSharpNodeType.ATTRIBUTE_LIST).equals(child.getType())) {
                continue;
            }
            var attrs = new ArrayList<TSNode>();
            collectNodesByType(child, Set.of(nodeType(CSharpNodeType.ATTRIBUTE)), attrs);
            for (TSNode attr : attrs) {
                TSNode nameNode = attr.getChildByFieldName(FIELD_NAME);
                if (nameNode == null) {
                    continue;
                }
                String name = sourceContent.substringFrom(nameNode).strip().toLowerCase(Locale.ROOT);
                if (name.endsWith("attribute")) {
                    name = name.substring(0, name.length() - "attribute".length());
                }
                String simple = lastDotSegment(name);
                if (TEST_METHOD_ATTRIBUTES.contains(simple)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String invocationMethodName(TSNode functionNode, SourceContent sourceContent) {
        if (nodeType(CSharpNodeType.MEMBER_ACCESS_EXPRESSION).equals(functionNode.getType())) {
            TSNode nameNode = functionNode.getChildByFieldName(FIELD_NAME);
            if (nameNode != null) {
                return sourceContent.substringFrom(nameNode).strip().toLowerCase(Locale.ROOT);
            }
            return lastDotSegment(
                    sourceContent.substringFrom(functionNode).strip().toLowerCase(Locale.ROOT));
        }
        if (nodeType(CSharpNodeType.IDENTIFIER).equals(functionNode.getType())
                || nodeType(CSharpNodeType.GENERIC_NAME).equals(functionNode.getType())) {
            return sourceContent.substringFrom(functionNode).strip().toLowerCase(Locale.ROOT);
        }
        TSNode expression = functionNode.getChildByFieldName(FIELD_EXPRESSION);
        if (expression != null) {
            return invocationMethodName(expression, sourceContent);
        }
        return "";
    }

    private static List<TSNode> argumentNodes(TSNode call) {
        TSNode args = call.getChildByFieldName(FIELD_ARGUMENTS);
        if (args == null) {
            args = firstNamedChildOfType(call, nodeType(CSharpNodeType.ARGUMENT_LIST));
        }
        if (args == null) {
            return List.of();
        }
        var out = new ArrayList<TSNode>();
        for (int i = 0; i < args.getNamedChildCount(); i++) {
            TSNode child = args.getNamedChild(i);
            if (child != null) {
                TSNode value = child.getChildByFieldName("expression");
                out.add(value != null ? value : child);
            }
        }
        return List.copyOf(out);
    }

    private static boolean containsOverspecifiedLiteral(
            List<TSNode> args, SourceContent sourceContent, TestAssertionWeights weights) {
        return args.stream()
                .anyMatch(arg -> nodeType(CSharpNodeType.STRING_LITERAL).equals(arg.getType())
                        && sourceContent.substringFrom(arg).length() >= weights.largeLiteralLengthThreshold());
    }

    private static boolean sameExpression(TSNode left, TSNode right, SourceContent sourceContent) {
        return sourceContent
                .substringFrom(left)
                .strip()
                .equals(sourceContent.substringFrom(right).strip());
    }

    private static boolean isConstantExpression(TSNode node, SourceContent sourceContent) {
        String type = node.getType();
        if (nodeType(CSharpNodeType.STRING_LITERAL).equals(type)
                || nodeType(CSharpNodeType.CHARACTER_LITERAL).equals(type)
                || nodeType(CSharpNodeType.INTEGER_LITERAL).equals(type)
                || nodeType(CSharpNodeType.REAL_LITERAL).equals(type)
                || nodeType(CSharpNodeType.NULL_LITERAL).equals(type)
                || nodeType(CSharpNodeType.BOOLEAN_LITERAL).equals(type)) {
            return true;
        }
        String text = sourceContent.substringFrom(node).strip();
        return text.matches("-?\\d+(\\.\\d+)?")
                || text.equals("true")
                || text.equals("false")
                || text.equals("null")
                || (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
                || (text.length() >= 3 && text.startsWith("'") && text.endsWith("'"));
    }

    private static boolean isBooleanTrueLiteral(TSNode node, SourceContent sourceContent) {
        return sourceContent.substringFrom(node).strip().equalsIgnoreCase("true");
    }

    private static boolean isBooleanFalseLiteral(TSNode node, SourceContent sourceContent) {
        return sourceContent.substringFrom(node).strip().equalsIgnoreCase("false");
    }

    private static @Nullable TSNode firstNamedChildOfType(TSNode node, String candidateType) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null && candidateType.equals(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private record AssertionSignal(
            String kind,
            int baseScore,
            boolean shallow,
            boolean meaningful,
            int startByte,
            List<String> reasons,
            String excerpt) {}

    @Override
    protected String formatFieldSignature(
            TSNode fieldNode,
            SourceContent sourceContent,
            String exportPrefix,
            String signatureText,
            String simpleName,
            String baseIndent,
            ProjectFile file) {
        String nodeType = fieldNode.getType();

        TSNode fieldDecl = null;
        TSNode varDecl = null;
        TSNode declarator = null;

        // If we captured the whole field_declaration, try to locate the variable_declaration child.
        if (FIELD_DECLARATION.equals(nodeType) || EVENT_FIELD_DECLARATION.equals(nodeType)) {
            fieldDecl = fieldNode;
            // Some grammars expose the variable_declaration via a field name, others as a plain child.
            varDecl = fieldNode.getChildByFieldName("declaration");
            if (varDecl == null) {
                for (TSNode child : fieldNode.getChildren()) {
                    if (CSharpTreeSitterNodeTypes.VARIABLE_DECLARATION.equals(child.getType())) {
                        varDecl = child;
                        break;
                    }
                }
            }
            if (varDecl != null) {
                declarator = findDeclarator(
                                varDecl,
                                simpleName,
                                sourceContent,
                                CSharpTreeSitterNodeTypes.VARIABLE_DECLARATOR,
                                "name")
                        .orElse(null);
            }
        } else if (CSharpTreeSitterNodeTypes.VARIABLE_DECLARATOR.equals(nodeType)) {
            // If the capture was the declarator itself, walk up to its variable_declaration and field_declaration
            declarator = fieldNode;
            varDecl = fieldNode.getParent();
            if (varDecl != null && CSharpTreeSitterNodeTypes.VARIABLE_DECLARATION.equals(varDecl.getType())) {
                fieldDecl = varDecl.getParent();
            }
        }

        if (fieldDecl != null && varDecl != null && declarator != null) {
            TSNode typeNode = varDecl.getChildByFieldName("type");
            if (typeNode != null) {
                StringBuilder modifiersBuilder = new StringBuilder();
                for (TSNode child : fieldDecl.getChildren()) {
                    if (child.getEndByte() > varDecl.getStartByte()) {
                        break;
                    }
                    String childType = child.getType();
                    if ("modifier".equals(childType)) {
                        String text = sourceContent.substringFrom(child).strip();
                        if (!text.isEmpty()) {
                            modifiersBuilder.append(text).append(" ");
                        }
                    }
                }

                String modifiers = modifiersBuilder.toString();
                String typeStr = sourceContent.substringFrom(typeNode).strip();

                TSNode nameNode = declarator.getChildByFieldName("name");
                String nameStr =
                        nameNode != null ? sourceContent.substringFrom(nameNode).strip() : simpleName;

                // Locate the initializer expression. In C#, it might be inside an equals_value_clause
                // or a direct child of the declarator depending on the grammar version/context.
                TSNode expression = null;
                TSNode valueClause = null;
                for (TSNode child : declarator.getNamedChildren()) {
                    if (EQUALS_VALUE_CLAUSE.equals(child.getType())) {
                        valueClause = child;
                        break;
                    }
                    // If we find a literal directly, use it
                    if (isLiteralType(child.getType())) {
                        expression = child;
                        break;
                    }
                }

                if (valueClause != null) {
                    expression = valueClause.getChildByFieldName("value");
                    if (expression == null) {
                        // Fallback: first named child in the clause that isn't the '=' operator
                        expression = valueClause.getChildren().stream()
                                .filter(child -> child.isNamed() && !"=".equals(child.getType()))
                                .findFirst()
                                .orElse(null);
                    }
                }

                String initializerStr = "";
                if (expression != null) {
                    TSNode literalNode = findLiteralNode(expression);
                    if (literalNode != null) {
                        initializerStr =
                                " = " + sourceContent.substringFrom(literalNode).strip();
                    }
                }

                String full = (modifiers + typeStr + " " + nameStr + initializerStr + ";").strip();
                return baseIndent + full;
            }
        }

        // Fallback: use provided signatureText
        String fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();
        if ((FIELD_DECLARATION.equals(nodeType) || EVENT_FIELD_DECLARATION.equals(nodeType))
                && !fullSignature.endsWith(";")) {
            fullSignature += ";";
        }
        return baseIndent + fullSignature;
    }

    private boolean isLiteralType(@Nullable String type) {
        if (type == null) return false;
        return type.endsWith("_literal")
                || BOOLEAN_LITERAL.equals(type)
                || INTEGER_LITERAL.equals(type)
                || REAL_LITERAL.equals(type)
                || CHARACTER_LITERAL.equals(type)
                || STRING_LITERAL.equals(type)
                || NULL_LITERAL.equals(type)
                || TRUE_KEYWORD.equals(type)
                || FALSE_KEYWORD.equals(type)
                || NULL_KEYWORD.equals(type);
    }

    private @Nullable TSNode findLiteralNode(@Nullable TSNode node) {
        if (node == null) return null;
        String type = node.getType();

        // 1. Direct hit
        if (isLiteralType(type)) return node;

        // 2. Only descend into specific wrapper nodes to avoid finding literals inside complex expressions
        if (PARENTHESIZED_EXPRESSION.equals(type) || LITERAL.equals(type)) {
            for (TSNode child : node.getNamedChildren()) {
                TSNode found = findLiteralNode(child);
                if (found != null) return found;
            }
        }

        return null;
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForCSharp(reference);
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return CaptureNames.CONSTRUCTOR_DEFINITION.equals(captureName);
    }

    @Override
    protected @Nullable CodeUnit createImplicitConstructor(CodeUnit enclosingClass, String classCaptureName) {
        return null;
    }

    @Override
    public Set<CodeUnit> testFilesToCodeUnits(Collection<ProjectFile> files) {
        var unitsInFiles = AnalyzerUtil.getTestDeclarationsWithLogging(this, files)
                .filter(CodeUnit::isClass)
                .collect(Collectors.toSet());

        return AnalyzerUtil.coalesceNestedUnits(this, unitsInFiles);
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

                        Set<String> testAttributes = Set.of(
                                "Test",
                                "Fact",
                                "Theory",
                                "TestCase",
                                "TestMethod",
                                "DataTestMethod",
                                "SetUp",
                                "TearDown");

                        while (cursor.nextMatch(match)) {
                            boolean hasTestMarker = false;
                            String capturedAttrName = null;

                            for (var capture : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(capture.getIndex());
                                TSNode node = capture.getNode();
                                if (node == null) continue;

                                if (TEST_MARKER.equals(captureName)) {
                                    hasTestMarker = true;
                                } else if ("test_attr".equals(captureName)) {
                                    capturedAttrName = sourceContent.substringFrom(node);
                                }
                            }

                            if (hasTestMarker && capturedAttrName != null) {
                                String normalizedName = capturedAttrName;
                                if (normalizedName.endsWith("Attribute")) {
                                    normalizedName =
                                            normalizedName.substring(0, normalizedName.length() - "Attribute".length());
                                }
                                final String finalName = normalizedName;
                                if (testAttributes.stream()
                                        .anyMatch(attr -> finalName.equals(attr) || finalName.endsWith("." + attr))) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                },
                false);
    }
}
