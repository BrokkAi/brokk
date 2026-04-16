package ai.brokk.analyzer;

import static ai.brokk.analyzer.python.PythonTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.project.ICoreProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

public final class PythonAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider, TypeHierarchyProvider {
    // Python's "last wins" behavior is handled by TreeSitterAnalyzer's addTopLevelCodeUnit().

    private static final Pattern WILDCARD_IMPORT_PATTERN = Pattern.compile("^from\\s+(.+?)\\s+import\\s+\\*");
    private static final Set<String> PYTHON_LOG_BARE_NAMES = Set.of("log", "logger", "warning", "error", "exception");
    private static final Set<String> PYTHON_LOG_RECEIVER_NAMES = Set.of("log", "logger");
    private static final Set<String> PYTHON_LOG_RECEIVER_EXTRA_SUFFIXES = Set.of("logging");
    private static final Set<String> PYTHON_LOG_METHOD_NAMES = Set.of("log", "warning", "error", "exception");

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return ClassNameExtractor.extractForPython(reference);
    }

    @Override
    public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
        checkStale("commentDensity");
        if (!"py".equals(cu.source().extension())) {
            return Optional.empty();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(cu.source(), COMMENT_NODE_TYPES);
        return Optional.of(buildRollUpStats(cu, counts));
    }

    @Override
    public List<CommentDensityStats> commentDensityByTopLevel(ProjectFile file) {
        checkStale("commentDensityByTopLevel");
        if (!"py".equals(file.extension())) {
            return List.of();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(file, COMMENT_NODE_TYPES);
        List<CommentDensityStats> rows = new ArrayList<>();
        for (CodeUnit top : getTopLevelDeclarations(file)) {
            rows.add(buildRollUpStats(top, counts));
        }
        return List.copyOf(rows);
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
        var functions = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(FUNCTION_DEFINITION), functions);
        var findings = new ArrayList<TestSmellCandidate>();
        for (TSNode function : functions) {
            if (isTestFunction(function, sourceContent)) {
                analyzeTestFunction(file, function, sourceContent, weights, findings);
            }
        }
        return findings.stream()
                .sorted(Comparator.comparingInt(TestSmellCandidate::score)
                        .reversed()
                        .thenComparing(c -> c.smell().file().toString())
                        .thenComparing(c -> c.smell().enclosingFqName())
                        .thenComparingInt(TestSmellCandidate::startByte))
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private void analyzeTestFunction(
            ProjectFile file,
            TSNode function,
            SourceContent sourceContent,
            TestAssertionWeights weights,
            List<TestSmellCandidate> out) {
        TSNode body = function.getChildByFieldName(FIELD_BODY);
        if (body == null) {
            body = firstNamedChildOfType(function, BLOCK);
        }
        if (body == null) {
            return;
        }
        var signals = new ArrayList<AssertionSignal>();
        var assertStatements = new ArrayList<TSNode>();
        collectNodesByType(body, Set.of(ASSERT_STATEMENT), assertStatements);
        assertStatements.stream()
                .map(assertNode -> classifyAssertStatement(assertNode, sourceContent, weights))
                .forEach(signals::add);
        var calls = new ArrayList<TSNode>();
        collectNodesByType(body, Set.of(CALL), calls);
        calls.stream()
                .map(call -> assertionSignal(call, sourceContent, weights))
                .flatMap(Optional::stream)
                .forEach(signals::add);

        String enclosing = enclosingCodeUnit(
                        file,
                        function.getStartPoint().getRow(),
                        function.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        int assertionCount = signals.size();
        if (assertionCount == 0) {
            addTestSmell(
                    file,
                    enclosing,
                    TEST_ASSERTION_KIND_NO_ASSERTIONS,
                    weights.noAssertionWeight(),
                    0,
                    List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                    sourceContent.substringFrom(function),
                    function.getStartByte(),
                    out);
            return;
        }

        signals.stream()
                .filter(signal -> signal.baseScore() > 0)
                .forEach(signal -> addTestSmell(
                        file,
                        enclosing,
                        signal.kind(),
                        signal.baseScore(),
                        assertionCount,
                        signal.reasons(),
                        signal.excerpt(),
                        signal.startByte(),
                        out));

        boolean allShallow = signals.stream().allMatch(AssertionSignal::shallow);
        if (allShallow) {
            int score = weights.shallowAssertionOnlyWeight()
                    - meaningfulAssertionCredit(signals, weights, AssertionSignal::meaningful);
            if (score > 0) {
                addTestSmell(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_SHALLOW_ONLY,
                        score,
                        assertionCount,
                        List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                        sourceContent.substringFrom(function),
                        function.getStartByte(),
                        out);
            }
        }
    }

    private AssertionSignal classifyAssertStatement(
            TSNode assertNode, SourceContent sourceContent, TestAssertionWeights weights) {
        TSNode expression = firstNamedChild(assertNode);
        int score = 0;
        var reasons = new ArrayList<String>();
        String kind = TEST_ASSERTION_KIND_PY_ASSERT;
        boolean shallow = false;
        boolean meaningful = true;
        if (expression != null) {
            if (TRUE.equals(expression.getType())) {
                score += weights.constantTruthWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_TRUTH);
                kind = TEST_ASSERTION_KIND_CONSTANT_TRUTH;
                meaningful = false;
            } else if (isSelfComparison(expression, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            } else if (isNullnessCheck(expression, sourceContent)) {
                score += weights.nullnessOnlyWeight();
                reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
                kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
                shallow = true;
                meaningful = false;
            }
        }
        return new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                assertNode.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(assertNode));
    }

    private Optional<AssertionSignal> assertionSignal(
            TSNode call, SourceContent sourceContent, TestAssertionWeights weights) {
        TSNode function = call.getChildByFieldName(FIELD_FUNCTION);
        if (function == null) {
            return Optional.empty();
        }
        String methodName = callableName(function, sourceContent);
        if (methodName.isBlank()) {
            return Optional.empty();
        }
        if (UNITTEST_ASSERTION_NAMES.contains(methodName)) {
            return Optional.of(classifyUnittestAssertion(call, methodName, sourceContent, weights));
        }
        if (MOCK_ASSERTION_NAMES.contains(methodName)) {
            return Optional.of(new AssertionSignal(
                    TEST_ASSERTION_KIND_MOCK_VERIFICATION,
                    0,
                    false,
                    true,
                    call.getStartByte(),
                    List.of(),
                    sourceContent.substringFrom(call)));
        }
        if (PYTEST_RAISES.equals(methodName) && isReceiverNamed(function, PYTEST, sourceContent)) {
            return Optional.of(new AssertionSignal(
                    TEST_ASSERTION_KIND_PYTEST_RAISES,
                    0,
                    false,
                    true,
                    call.getStartByte(),
                    List.of(),
                    sourceContent.substringFrom(call)));
        }
        return Optional.empty();
    }

    private AssertionSignal classifyUnittestAssertion(
            TSNode call, String methodName, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> args = argumentNodes(call);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean shallow = SHALLOW_ASSERTION_NAMES.contains(methodName);
        boolean meaningful = !shallow;
        String kind = TEST_ASSERTION_KIND_UNITTEST;
        if (("assertTrue".equals(methodName) || "assertFalse".equals(methodName)) && !args.isEmpty()) {
            TSNode arg = args.getFirst();
            boolean constantTruth = ("assertTrue".equals(methodName) && TRUE.equals(arg.getType()))
                    || ("assertFalse".equals(methodName) && FALSE.equals(arg.getType()));
            if (constantTruth) {
                score += weights.constantTruthWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_TRUTH);
                kind = TEST_ASSERTION_KIND_CONSTANT_TRUTH;
                meaningful = false;
            }
        }
        if (("assertEqual".equals(methodName) || "assertIs".equals(methodName)) && args.size() >= 2) {
            TSNode expected = args.get(0);
            TSNode actual = args.get(1);
            if (isConstantExpression(expected) && isConstantExpression(actual)) {
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
        if (SHALLOW_ASSERTION_NAMES.contains(methodName)) {
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
        return new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                call.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(call));
    }

    private static boolean isTestFunction(TSNode function, SourceContent sourceContent) {
        TSNode parent = function.getParent();
        if (parent != null
                && DECORATED_DEFINITION.equals(parent.getType())
                && hasPytestFixtureDecorator(parent, sourceContent)) {
            return false;
        }
        TSNode nameNode = function.getChildByFieldName(FIELD_NAME);
        if (nameNode != null && sourceContent.substringFrom(nameNode).startsWith("test_")) {
            return true;
        }
        return parent != null
                && DECORATED_DEFINITION.equals(parent.getType())
                && parent.getNamedChildren().stream()
                        .anyMatch(child -> DECORATOR.equals(child.getType()) && isPytestMarkNode(child, sourceContent));
    }

    private static boolean hasPytestFixtureDecorator(TSNode decoratedDefinition, SourceContent sourceContent) {
        return decoratedDefinition.getNamedChildren().stream()
                .filter(child -> DECORATOR.equals(child.getType()))
                .anyMatch(decorator -> {
                    TSNode expression = decorator.getNamedChild(0);
                    if (expression == null) {
                        return false;
                    }
                    TSNode target = CALL.equals(expression.getType())
                            ? expression.getChildByFieldName(FIELD_FUNCTION)
                            : expression;
                    return target != null
                            && attributeSegments(target, sourceContent).equals(List.of(PYTEST, FIXTURE));
                });
    }

    private static boolean isPytestMarkNode(TSNode decoratorNode, SourceContent sourceContent) {
        TSNode expression = decoratorNode.getNamedChild(0);
        if (expression == null) {
            return false;
        }
        TSNode target = CALL.equals(expression.getType()) ? expression.getChildByFieldName(FIELD_FUNCTION) : expression;
        if (target == null) {
            return false;
        }
        var segments = attributeSegments(target, sourceContent);
        return segments.size() >= 2 && PYTEST.equals(segments.get(0)) && MARK.equals(segments.get(1));
    }

    private static List<String> attributeSegments(TSNode node, SourceContent sourceContent) {
        var segments = new ArrayList<String>();
        TSNode current = node;
        while (current != null) {
            if (ATTRIBUTE.equals(current.getType())) {
                TSNode attributeNode = current.getChildByFieldName(FIELD_ATTRIBUTE);
                if (attributeNode != null) {
                    segments.add(0, sourceContent.substringFrom(attributeNode));
                }
                current = current.getChildByFieldName(FIELD_OBJECT);
            } else if (IDENTIFIER.equals(current.getType())) {
                segments.add(0, sourceContent.substringFrom(current));
                break;
            } else {
                break;
            }
        }
        return List.copyOf(segments);
    }

    private static String callableName(TSNode function, SourceContent sourceContent) {
        if (IDENTIFIER.equals(function.getType())) {
            return sourceContent.substringFrom(function).strip();
        }
        if (ATTRIBUTE.equals(function.getType())) {
            TSNode attributeNode = function.getChildByFieldName(FIELD_ATTRIBUTE);
            return attributeNode == null
                    ? ""
                    : sourceContent.substringFrom(attributeNode).strip();
        }
        return "";
    }

    private static boolean isReceiverNamed(TSNode function, String name, SourceContent sourceContent) {
        if (!ATTRIBUTE.equals(function.getType())) {
            return false;
        }
        TSNode objectNode = function.getChildByFieldName(FIELD_OBJECT);
        return objectNode != null
                && name.equals(sourceContent.substringFrom(objectNode).strip());
    }

    private static List<TSNode> argumentNodes(TSNode call) {
        TSNode args = call.getChildByFieldName(FIELD_ARGUMENTS);
        if (args == null) {
            args = firstNamedChildOfType(call, ARGUMENT_LIST);
        }
        if (args == null) {
            return List.of();
        }
        var out = new ArrayList<TSNode>();
        for (int i = 0; i < args.getNamedChildCount(); i++) {
            TSNode child = args.getNamedChild(i);
            if (child != null) {
                out.add(child);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isSelfComparison(TSNode node, SourceContent sourceContent) {
        if (!COMPARISON_OPERATOR.equals(node.getType())) {
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

    private static boolean isNullnessCheck(TSNode node, SourceContent sourceContent) {
        if (!COMPARISON_OPERATOR.equals(node.getType())) {
            return false;
        }
        return sourceContent.substringFrom(node).contains("None");
    }

    private static boolean sameExpression(TSNode left, TSNode right, SourceContent sourceContent) {
        return sourceContent
                .substringFrom(left)
                .strip()
                .equals(sourceContent.substringFrom(right).strip());
    }

    private static boolean isConstantExpression(TSNode node) {
        return CONSTANT_LITERAL_TYPES.contains(node.getType());
    }

    private static boolean containsOverspecifiedLiteral(
            List<TSNode> args, SourceContent sourceContent, TestAssertionWeights weights) {
        return args.stream()
                .anyMatch(arg -> STRING.equals(arg.getType())
                        && sourceContent.substringFrom(arg).length() >= weights.largeLiteralLengthThreshold());
    }

    private static int meaningfulAssertionCredit(
            List<AssertionSignal> assertions,
            TestAssertionWeights weights,
            java.util.function.Predicate<AssertionSignal> predicate) {
        long count = assertions.stream().filter(predicate).count();
        int creditable = Math.min((int) count, Math.max(0, weights.meaningfulAssertionCreditCap()));
        return Math.max(0, weights.meaningfulAssertionCredit()) * creditable;
    }

    private static @Nullable TSNode firstNamedChild(TSNode node) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null) {
                return child;
            }
        }
        return null;
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

    private static void addTestSmell(
            ProjectFile file,
            String enclosing,
            String assertionKind,
            int score,
            int assertionCount,
            List<String> reasons,
            String excerptSource,
            int startByte,
            List<TestSmellCandidate> out) {
        if (score <= 0 || reasons.isEmpty()) {
            return;
        }
        var smell = new TestAssertionSmell(
                file,
                enclosing,
                assertionKind,
                score,
                assertionCount,
                List.copyOf(reasons),
                compactCatchExcerpt(excerptSource));
        out.add(new TestSmellCandidate(smell, startByte));
    }

    private record TestSmellCandidate(TestAssertionSmell smell, int startByte) {
        int score() {
            return smell.score();
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

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var clauses = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(EXCEPT_CLAUSE), clauses);
        return clauses.stream()
                .map(clause -> analyzeExceptClause(file, clause, sourceContent, weights))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(ExceptionHandlingSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(ExceptionHandlingSmell::enclosingFqName))
                .toList();
    }

    private Optional<ExceptionHandlingSmell> analyzeExceptClause(
            ProjectFile file, TSNode exceptClause, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode bodyNode = exceptClause.getNamedChildren().stream()
                .filter(child -> BLOCK.equals(child.getType()))
                .findFirst()
                .orElse(null);
        if (bodyNode == null) {
            return Optional.empty();
        }

        int bodyStatements = countBodyExpressions(bodyNode);
        String bodyText = sourceContent.substringFrom(bodyNode);
        boolean hasAnyComment = bodyText.contains("#");
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean raisePresent = hasDescendantOfType(bodyNode, RAISE_STATEMENT);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !raisePresent;

        String catchType = extractExceptType(exceptClause, sourceContent);
        int score = 0;
        var reasons = new ArrayList<String>();
        if (catchType.equals("<bare>") || catchType.contains("BaseException")) {
            score += weights.genericThrowableWeight();
            reasons.add("generic-catch:BaseException");
        } else if (catchType.contains("Exception")) {
            score += weights.genericExceptionWeight();
            reasons.add("generic-catch:Exception");
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
                        exceptClause.getStartPoint().getRow(),
                        exceptClause.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        return Optional.of(new ExceptionHandlingSmell(
                file,
                enclosing,
                catchType,
                score,
                bodyStatements,
                List.copyOf(reasons),
                compactCatchExcerpt(sourceContent.substringFrom(exceptClause))));
    }

    private static int countBodyExpressions(TSNode bodyNode) {
        int expressions = 0;
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode child = bodyNode.getNamedChild(i);
            if (child == null) {
                continue;
            }
            if (CATCH_BODY_MEANINGFUL_STATEMENT_TYPES.contains(child.getType())) {
                expressions++;
            }
        }
        return expressions;
    }

    private static String extractExceptType(TSNode exceptClause, SourceContent sourceContent) {
        String text = sourceContent.substringFrom(exceptClause).strip();
        if (!text.startsWith("except")) {
            return "<unknown>";
        }
        int colonIdx = text.indexOf(':');
        String head = colonIdx >= 0 ? text.substring(0, colonIdx) : text;
        String remainder = head.substring("except".length()).strip();
        if (remainder.isEmpty()) {
            return "<bare>";
        }
        int asIdx = remainder.indexOf(" as ");
        return (asIdx >= 0 ? remainder.substring(0, asIdx) : remainder).strip();
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode statement = firstNonCommentNamedChild(bodyNode, COMMENT_NODE_TYPES);
        if (statement == null || !EXPRESSION_STATEMENT.equals(statement.getType())) {
            return false;
        }
        TSNode callNode = findFirstNamedDescendant(statement, CALL);
        if (callNode == null) {
            return false;
        }
        TSNode functionNode = callNode.getChildByFieldName(FIELD_FUNCTION);
        if (functionNode == null) {
            return false;
        }
        if (IDENTIFIER.equals(functionNode.getType())) {
            String bare = sourceContent.substringFrom(functionNode).strip().toLowerCase(Locale.ROOT);
            return PYTHON_LOG_BARE_NAMES.contains(bare);
        }
        if (!ATTRIBUTE.equals(functionNode.getType())) {
            return false;
        }
        TSNode objectNode = functionNode.getChildByFieldName(FIELD_OBJECT);
        TSNode attributeNode = functionNode.getChildByFieldName(FIELD_ATTRIBUTE);
        if (objectNode == null || attributeNode == null) {
            return false;
        }
        String receiver = sourceContent.substringFrom(objectNode).strip().toLowerCase(Locale.ROOT);
        String method = sourceContent.substringFrom(attributeNode).strip().toLowerCase(Locale.ROOT);
        boolean loggerLikeReceiver = PYTHON_LOG_RECEIVER_NAMES.contains(receiver)
                || PYTHON_LOG_RECEIVER_NAMES.stream().anyMatch(name -> receiver.endsWith("." + name))
                || PYTHON_LOG_RECEIVER_EXTRA_SUFFIXES.stream().anyMatch(receiver::endsWith);
        boolean loggerLikeMethod = PYTHON_LOG_METHOD_NAMES.contains(method);
        return loggerLikeReceiver && loggerLikeMethod;
    }

    private static String compactCatchExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private CommentDensityStats buildRollUpStats(CodeUnit cu, Map<String, CommentLineBreakdown> counts) {
        CommentLineBreakdown own = counts.getOrDefault(cu.fqName(), new CommentLineBreakdown(0, 0));
        int span = rangesOf(cu).stream()
                .mapToInt(r -> r.endLine() - r.startLine() + 1)
                .sum();
        String path = cu.source().toString();
        if (!cu.isClass()) {
            return new CommentDensityStats(
                    cu.fqName(),
                    path,
                    own.headerLines(),
                    own.inlineLines(),
                    span,
                    own.headerLines(),
                    own.inlineLines(),
                    span);
        }
        int rh = own.headerLines();
        int ri = own.inlineLines();
        int rs = span;
        for (CodeUnit ch : getDirectChildren(cu)) {
            CommentDensityStats child = buildRollUpStats(ch, counts);
            rh += child.rolledUpHeaderCommentLines();
            ri += child.rolledUpInlineCommentLines();
            rs += child.rolledUpSpanLines();
        }
        return new CommentDensityStats(cu.fqName(), path, own.headerLines(), own.inlineLines(), span, rh, ri, rs);
    }

    @Override
    public int computeCyclomaticComplexity(CodeUnit cu) {
        if (!cu.isFunction()) return 0;

        return getSource(cu, false)
                .map(source -> {
                    try (TSTree tree = getTSParser().parseStringOrThrow(null, source)) {
                        TSNode root = tree.getRootNode();
                        if (root == null) return 1;

                        SourceContent content = SourceContent.of(source);
                        int complexity = 1; // Base complexity

                        Deque<TSNode> stack = new ArrayDeque<>();
                        stack.push(root);

                        while (!stack.isEmpty()) {
                            TSNode node = stack.pop();
                            String type = node.getType();
                            if (type == null) {
                                continue;
                            }

                            switch (type) {
                                case IF_STATEMENT,
                                        ELIF_CLAUSE,
                                        FOR_STATEMENT,
                                        WHILE_STATEMENT,
                                        EXCEPT_CLAUSE,
                                        CONDITIONAL_EXPRESSION,
                                        CASE_CLAUSE -> complexity++;
                                case BOOLEAN_OPERATOR -> {
                                    String op = content.substringFrom(node);
                                    if (op.contains("and") || op.contains("or")) {
                                        complexity++;
                                    }
                                }
                            }

                            for (int i = 0; i < node.getNamedChildCount(); i++) {
                                TSNode child = node.getNamedChild(i);
                                if (child != null) {
                                    stack.push(child);
                                }
                            }
                        }
                        return complexity;
                    } catch (Exception e) {
                        log.warn("Failed to compute complexity for {} using AST; falling back", cu.fqName(), e);
                        return super.computeCyclomaticComplexity(cu);
                    }
                })
                .orElse(0);
    }

    @Override
    public List<String> getTestModules(Collection<ProjectFile> files) {
        return files.stream()
                .map(file -> resolveModuleInfo(file).moduleQualifiedPackage())
                .distinct()
                .sorted()
                .toList();
    }

    // PY_LANGUAGE field removed, createTSLanguage will provide new instances.
    private static final LanguageSyntaxProfile PY_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of(CLASS_DEFINITION),
            Set.of(FUNCTION_DEFINITION),
            Set.of(ASSIGNMENT, TYPED_PARAMETER),
            Set.of(),
            Set.of(DECORATOR, DECORATED_DEFINITION),
            IMPORT_DECLARATION,
            "name", // identifierFieldName
            "body", // bodyFieldName
            "parameters", // parametersFieldName
            "return_type", // returnTypeFieldName
            "", // typeParametersFieldName (Python doesn't have explicit type parameters)
            Map.of( // captureConfiguration
                    CaptureNames.CLASS_DEFINITION, SkeletonType.CLASS_LIKE,
                    CaptureNames.FUNCTION_DEFINITION, SkeletonType.FUNCTION_LIKE,
                    CaptureNames.FIELD_DEFINITION, SkeletonType.FIELD_LIKE),
            "async", // asyncKeywordNodeType
            Set.of() // modifierNodeTypes
            );

    public PythonAnalyzer(ICoreProject project) {
        this(project, ProgressListener.NOOP);
    }

    public PythonAnalyzer(ICoreProject project, ProgressListener listener) {
        super(project, Languages.PYTHON, listener);
    }

    private PythonAnalyzer(
            ICoreProject project, AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache cache) {
        super(project, Languages.PYTHON, state, listener, cache);
    }

    public static PythonAnalyzer fromState(ICoreProject project, AnalyzerState state, ProgressListener listener) {
        return new PythonAnalyzer(project, state, listener, null);
    }

    @Override
    protected IAnalyzer newSnapshot(
            AnalyzerState state, ProgressListener listener, @Nullable AnalyzerCache previousCache) {
        return new PythonAnalyzer(getProject(), state, listener, previousCache);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterPython();
    }

    @Override
    protected Optional<String> getQueryResource(QueryType type) {
        return switch (type) {
            case DEFINITIONS -> Optional.of("treesitter/python/definitions.scm");
            case IMPORTS -> Optional.of("treesitter/python/imports.scm");
            case IDENTIFIERS -> Optional.of("treesitter/python/identifiers.scm");
        };
    }

    /**
     * Encapsulates Python package/module resolution, handling __init__.py semantics in one place.
     * For __init__.py files, the last package segment becomes the module name to match Python import semantics
     * (e.g., "from mypackage import ClassName" works when ClassName is in mypackage/__init__.py).
     */
    private record PythonModuleInfo(String packageName, String moduleName) {
        /**
         * Returns the fully qualified module path (packageName.moduleName or just moduleName).
         * This is the prefix for all FQNs in this file.
         */
        String moduleQualifiedPackage() {
            return packageName.isEmpty() ? moduleName : packageName + "." + moduleName;
        }
    }

    /**
     * Resolves the package and module name for a Python file, handling __init__.py semantics.
     */
    private PythonModuleInfo resolveModuleInfo(ProjectFile file) {
        String rawPackage = getPackageNameForFile(file);

        // Extract module name from filename
        String moduleName = file.getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3);
        }

        // For __init__.py, fold last package segment into module name
        if (moduleName.equals("__init__") && !rawPackage.isEmpty()) {
            int lastDot = rawPackage.lastIndexOf('.');
            if (lastDot == -1) {
                // "mypackage" -> module="mypackage", pkg=""
                return new PythonModuleInfo("", rawPackage);
            } else {
                // "mypackage.subpkg" -> module="subpkg", pkg="mypackage"
                return new PythonModuleInfo(rawPackage.substring(0, lastDot), rawPackage.substring(lastDot + 1));
            }
        }

        return new PythonModuleInfo(rawPackage, moduleName);
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
        // packageName is already module-qualified (from determinePackageName via resolveModuleInfo)
        return switch (captureName) {
            case CaptureNames.CLASS_DEFINITION -> {
                log.trace(
                        "Creating class: simpleName='{}', scopeChain='{}', packageName='{}'",
                        simpleName,
                        scopeChain,
                        packageName);

                // Design: shortName = class hierarchy only (no module prefix)
                // Module is included in packageName for proper fqName construction
                // Use $ for class nesting, . for function scope
                String finalShortName;

                if (scopeChain.isEmpty()) {
                    // Top-level class: just "ClassName"
                    finalShortName = simpleName;
                } else if (scopeChain.getFirst().isFunctionScope()) {
                    // Function-local class: "func$LocalClass" or "func$Outer$Inner"
                    var first = scopeChain.getFirst();
                    if (scopeChain.size() == 1) {
                        // Direct child of function
                        finalShortName = first.name() + "$" + simpleName;
                    } else {
                        // Nested in class inside function
                        String restPart = normalizedRest(scopeChain);
                        finalShortName = first.name() + "$" + restPart + "$" + simpleName;
                    }
                } else {
                    // Class-nested: "Outer$Inner"
                    finalShortName = normalized(scopeChain) + "$" + simpleName;
                }

                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case CaptureNames.FUNCTION_DEFINITION -> {
                // Functions use . for member access
                String finalShortName;

                if (scopeChain.isEmpty()) {
                    // Top-level function: just "func"
                    finalShortName = simpleName;
                } else if (scopeChain.getFirst().isFunctionScope()) {
                    // Nested function or method in function-local class
                    var first = scopeChain.getFirst();
                    if (scopeChain.size() == 1) {
                        // Nested function inside function: "outer.inner"
                        finalShortName = first.name() + "." + simpleName;
                    } else {
                        // Method in function-local class: "func$Class.method"
                        finalShortName = first.name() + "$" + normalizedRest(scopeChain) + "." + simpleName;
                    }
                } else {
                    // Method in regular class: "Class.method" or "Outer$Inner.method"
                    finalShortName = normalized(scopeChain) + "." + simpleName;
                }

                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case CaptureNames.FIELD_DEFINITION -> {
                // Fields use . for member access
                String finalShortName;

                if (scopeChain.isEmpty()) {
                    // Top-level variable: just "varName"
                    finalShortName = simpleName;
                } else if (scopeChain.getFirst().isFunctionScope()) {
                    // Field in function-local class
                    var first = scopeChain.getFirst();
                    if (scopeChain.size() == 1) {
                        // Variable in function scope (unusual): "func.var"
                        finalShortName = first.name() + "." + simpleName;
                    } else {
                        finalShortName = first.name() + "$" + normalizedRest(scopeChain) + "." + simpleName;
                    }
                } else {
                    // Field in regular class: "Class.field"
                    finalShortName = normalized(scopeChain) + "." + simpleName;
                }

                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture: {} with name: {} and scopeChain: {}", captureName, simpleName, scopeChain);
                yield null;
            }
        };
    }

    /** Join all scope segment names with $ */
    private static String normalized(List<ScopeSegment> scopeChain) {
        return scopeChain.stream().map(ScopeSegment::name).collect(Collectors.joining("$"));
    }

    /** Join scope segment names after the first with $ */
    private static String normalizedRest(List<ScopeSegment> scopeChain) {
        return scopeChain.size() <= 1
                ? ""
                : scopeChain.subList(1, scopeChain.size()).stream()
                        .map(ScopeSegment::name)
                        .collect(Collectors.joining("$"));
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // Python query uses "@obj (#eq? @obj \"self\")" predicate helper, ignore the @obj capture
        return Set.of("obj");
    }

    @Override
    protected boolean shouldSkipNode(TSNode node, String captureName, SourceContent sourceContent) {
        // Skip bare class/function definitions if they are wrapped in a decorated_definition
        // (We rely on the decorated_definition capture instead to preserve decorators)
        if (CLASS_DEFINITION.equals(node.getType()) || FUNCTION_DEFINITION.equals(node.getType())) {
            TSNode current = node.getParent();
            while (current != null) {
                if (DECORATED_DEFINITION.equals(current.getType())) {
                    return true;
                }
                if (BLOCK.equals(current.getType()) || MODULE.equals(current.getType())) {
                    break;
                }
                current = current.getParent();
            }
        }

        // Skip property setters to avoid duplicates with property getters
        if (CaptureNames.FUNCTION_DEFINITION.equals(captureName) && DECORATED_DEFINITION.equals(node.getType())) {
            // Check if this is a property setter by looking at decorators
            for (TSNode child : node.getNamedChildren()) {
                if (DECORATOR.equals(child.getType())) {
                    TSNode decoratorChild = child.getNamedChild(0);
                    if (decoratorChild != null && ATTRIBUTE.equals(decoratorChild.getType())) {
                        // Get the decorator text using the inherited textSlice method
                        String decoratorText =
                                sourceContent.substringFrom(decoratorChild).trim();
                        // Skip property setters/deleters: match "<name>.(setter|deleter)" only
                        if (decoratorText.matches("[^.]+\\.(setter|deleter)")) {
                            log.trace("Skipping property setter/deleter with decorator: {}", decoratorText);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected boolean shouldReplaceOnDuplicate(CodeUnit existing, CodeUnit candidate) {
        // Python "last wins" semantics: duplicate definitions replace earlier ones
        // But only replace if same kind - a field shouldn't replace a class (e.g., "Base = FallbackBase")
        if (existing.kind() != candidate.kind()) {
            return false;
        }
        return candidate.isField() || candidate.isClass() || candidate.isFunction();
    }

    @Override
    protected boolean hasWrappingDecoratorNode() {
        // Python wraps decorated definitions in a decorated_definition node
        return true;
    }

    @Override
    protected TSNode extractContentFromDecoratedNode(
            TSNode decoratedNode,
            List<String> outDecoratorLines,
            SourceContent sourceContent,
            LanguageSyntaxProfile profile) {
        // Python's decorated_definition: decorators and actual definition are children
        // Process decorators and identify the actual content node
        TSNode nodeForContent = decoratedNode;
        if (DECORATED_DEFINITION.equals(decoratedNode.getType())) {
            for (TSNode child : decoratedNode.getChildren()) {
                String type = child.getType();
                if (DECORATOR.equals(type)) {
                    outDecoratorLines.add(sourceContent.substringFrom(child).stripLeading());
                } else if (CLASS_DEFINITION.equals(type) || FUNCTION_DEFINITION.equals(type)) {
                    nodeForContent = child;
                }
            }
        }
        return nodeForContent;
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
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
        String pyReturnTypeSuffix = !returnTypeText.isEmpty() ? " -> " + returnTypeText : "";
        // The 'indent' parameter is now "" when called from buildSignatureString,
        // so it's effectively ignored here for constructing the stored signature.
        String signature = String.format(
                "%s%sdef %s%s%s:", exportPrefix, asyncPrefix, functionName, paramsText, pyReturnTypeSuffix);

        TSNode bodyNode = funcNode.getChildByFieldName("body");
        boolean hasMeaningfulBody = false;
        if (bodyNode != null) {
            int childCount = bodyNode.getNamedChildCount();
            if (childCount > 1) {
                hasMeaningfulBody = true;
            } else if (childCount == 1) {
                TSNode firstChild = bodyNode.getNamedChild(0);
                hasMeaningfulBody = firstChild != null && !PASS_STATEMENT.equals(firstChild.getType());
            }
        }

        if (hasMeaningfulBody) {
            return signature + " " + bodyPlaceholder(); // Do not prepend indent here
        } else {
            return signature; // Do not prepend indent here
        }
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

        // Python field nodes from definitions.scm are typically 'expression_statement' wrapping 'assignment', or
        // 'typed_parameter'
        TSNode assignmentNode = fieldNode;
        if (EXPRESSION_STATEMENT.equals(fieldNode.getType()) && fieldNode.getNamedChildCount() > 0) {
            TSNode child = fieldNode.getNamedChild(0);
            if (child != null) {
                assignmentNode = child;
            }
        }

        TSNode valueNode = assignmentNode.getChildByFieldName("value");
        if (valueNode == null) {
            // Assignments will have a "left"/"right" property
            valueNode = assignmentNode.getChildByFieldName("right");
        }

        if (valueNode == null) {
            // Pure type annotation with no default value (e.g. x: int)
            return baseIndent + signatureText;
        }

        if (isLiteralType(valueNode.getType())) {
            return baseIndent + signatureText;
        }

        // For non-literals, omit the assignment from the skeleton completely
        return "";
    }

    private boolean isLiteralType(@Nullable String type) {
        if (type == null) return false;
        return type.endsWith("_literal")
                || type.equals(STRING)
                || type.equals(INTEGER)
                || type.equals(FLOAT)
                || type.equals(TRUE)
                || type.equals(FALSE)
                || type.equals(BOOLEAN)
                || type.equals(NONE);
    }

    @Override
    protected ResolvedNodes resolveSignatureNodes(
            TSNode definitionNode, String simpleName, SkeletonType refined, SourceContent sourceContent) {
        if (DECORATED_DEFINITION.equals(definitionNode.getType())) {
            for (TSNode child : definitionNode.getChildren()) {
                String type = child.getType();
                if (CLASS_DEFINITION.equals(type) || FUNCTION_DEFINITION.equals(type)) {
                    return new ResolvedNodes(child, child);
                }
            }
        }
        return super.resolveSignatureNodes(definitionNode, simpleName, refined, sourceContent);
    }

    @Override
    protected @Nullable String extractSignature(
            String captureName, TSNode definitionNode, SourceContent sourceContent) {
        TSNode targetNode = definitionNode;
        if (DECORATED_DEFINITION.equals(definitionNode.getType())) {
            for (TSNode child : definitionNode.getChildren()) {
                String type = child.getType();
                if (CLASS_DEFINITION.equals(type) || FUNCTION_DEFINITION.equals(type)) {
                    targetNode = child;
                    break;
                }
            }
        }
        return super.extractSignature(captureName, targetNode, sourceContent);
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
        return exportPrefix + signatureText; // Do not prepend baseIndent here
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return ""; // Python uses indentation, no explicit closer for classes/functions
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

                        var match = new TSQueryMatch();
                        while (cursor.nextMatch(match)) {
                            for (var cap : match.getCaptures()) {
                                String captureName = query.getCaptureNameForId(cap.getIndex());

                                TSNode node = cap.getNode();
                                if (node == null) {
                                    continue;
                                }

                                if (TEST_MARKER.equals(captureName)) {
                                    // Case A: Function name starting with test_
                                    if (IDENTIFIER.equals(node.getType())) {
                                        TSNode parent = node.getParent();
                                        if (parent != null && FUNCTION_DEFINITION.equals(parent.getType())) {
                                            TSNode nameNode = parent.getChildByFieldName(FIELD_NAME);
                                            if (nameNode != null
                                                    && nameNode.getStartByte() == node.getStartByte()
                                                    && nameNode.getEndByte() == node.getEndByte()) {
                                                String text = sourceContent.substringFrom(node);
                                                if (text.startsWith("test_")) {
                                                    return true;
                                                }
                                            }
                                        }
                                    }

                                    // Case B: Pytest marks
                                    if (DECORATOR.equals(node.getType())) {
                                        if (isPytestMark(node, sourceContent)) {
                                            return true;
                                        }
                                    }
                                }

                                // Case C: Logic from testFilesToCodeUnits - check for Test prefix on classes/functions
                                if (CaptureNames.CLASS_DEFINITION.equals(captureName)
                                        || CaptureNames.FUNCTION_DEFINITION.equals(captureName)) {
                                    TSNode nameNode = node.getChildByFieldName(FIELD_NAME);
                                    if (nameNode != null) {
                                        String name = sourceContent.substringFrom(nameNode);
                                        if (name.startsWith("test_") || name.startsWith("Test")) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return false;
                },
                false);
    }

    private boolean isPytestMark(TSNode decoratorNode, SourceContent sourceContent) {
        // decorator -> expression (named child 0)
        TSNode expression = decoratorNode.getNamedChild(0);
        if (expression == null) {
            return false;
        }

        TSNode target = expression;
        // If it's a call (e.g. @pytest.mark.parametrize(...)), unwrap to the callee
        if (CALL.equals(target.getType())) {
            target = target.getChildByFieldName(FIELD_FUNCTION);
        }

        if (target == null) {
            return false;
        }

        // Try AST navigation for attribute segments
        List<String> segments = new ArrayList<>();
        TSNode current = target;
        while (current != null) {
            if (ATTRIBUTE.equals(current.getType())) {
                TSNode attributeNameNode = current.getChildByFieldName(FIELD_ATTRIBUTE);
                if (attributeNameNode != null) {
                    segments.add(0, sourceContent.substringFrom(attributeNameNode));
                }
                current = current.getChildByFieldName(FIELD_OBJECT);
            } else if (IDENTIFIER.equals(current.getType())) {
                segments.add(0, sourceContent.substringFrom(current));
                break;
            } else {
                break;
            }
        }

        if (segments.size() >= 2 && PYTEST.equals(segments.get(0)) && MARK.equals(segments.get(1))) {
            return true;
        }

        // Fallback: minimal string check on the sliced expression
        String expressionText = sourceContent.substringFrom(expression);
        return expressionText.startsWith(PYTEST_MARK_PREFIX);
    }

    /**
     * Determines the package name for a Python file based on its directory structure
     * and __init__.py markers.
     *
     * @param file The Python file
     * @return The package name (dot-separated), or empty string if at root
     */
    private String getPackageNameForFile(ProjectFile file) {
        // Python's package naming is directory-based, relative to project root or __init__.py markers.
        Path projectRoot = getProject().getRoot();
        Path relPath = file.getRelPath();
        Path parentRel = relPath.getParent();

        // If the file is directly in the project root, the package path is empty
        if (parentRel == null || parentRel.toString().isEmpty()) {
            return "";
        }

        // Find the highest directory containing __init__.py between project root and the file's parent
        // Note: we must still check the filesystem for __init__.py existence.
        Path effectivePackageRootRel = null;
        Path currentRel = parentRel;
        while (currentRel != null) {
            if (Files.exists(projectRoot.resolve(currentRel).resolve("__init__.py"))) {
                effectivePackageRootRel = currentRel;
            }
            currentRel = currentRel.getParent();
        }

        // If no __init__.py found, it's a top-level module or in a non-package directory.
        if (effectivePackageRootRel == null) {
            return parentRel.toString().replace('/', '.').replace('\\', '.');
        }

        // The import root is the parent of the top-most package directory.
        Path importRootRel = effectivePackageRootRel.getParent();
        if (importRootRel == null) {
            return parentRel.toString().replace('/', '.').replace('\\', '.');
        }

        return importRootRel.relativize(parentRel).toString().replace('/', '.').replace('\\', '.');
    }

    @Override
    protected String determinePackageName(
            ProjectFile file, TSNode definitionNode, TSNode rootNode, SourceContent sourceContent) {
        // Python's package naming is directory-based, relative to project root or __init__.py markers.
        // The definitionNode, rootNode, and src parameters are not used for Python package determination.
        // Returns module-qualified package (e.g., "mypkg.mod" not just "mypkg") for proper FQN construction.
        return resolveModuleInfo(file).moduleQualifiedPackage();
    }

    @Override
    protected String buildParentFqName(CodeUnit cu, String classChain, List<ScopeSegment> scopeChain) {
        // Design: shortName = class/function hierarchy, packageName = pkg.module
        // The scopeChain represents the nesting structure above this symbol
        // - Top-level: parent = packageName (module level)
        // - Nested class: Outer$Inner -> parent fqName = pkg.module.Outer
        // - Function-local: func$Local -> parent fqName = pkg.module.func
        // - Nested in func-local: func$Outer$Inner -> parent = pkg.module.func$Outer

        String packageName = cu.packageName();

        // TreeSitterAnalyzer only calls buildParentFqName for nested symbols
        assert !scopeChain.isEmpty() : "buildParentFqName should only be called with non-empty scopeChain";

        if (scopeChain.getFirst().isFunctionScope()) {
            // Function scope: func or func$Class
            var first = scopeChain.getFirst();
            if (scopeChain.size() == 1) {
                // Just function: parent fqName = pkg.module.func
                String parentFqn = packageName + "." + first.name();
                log.trace(
                        "Python parent lookup: scopeChain='{}', packageName='{}', returning '{}' (function parent)",
                        scopeChain,
                        packageName,
                        parentFqn);
                return parentFqn;
            } else {
                // Function + classes: func$Class -> parent = pkg.module.func$Class
                String parentFqn = packageName + "." + first.name() + "$" + normalizedRest(scopeChain);
                log.trace(
                        "Python parent lookup: scopeChain='{}', packageName='{}', first='{}', rest='{}', returning '{}'",
                        scopeChain,
                        packageName,
                        first.name(),
                        normalizedRest(scopeChain),
                        parentFqn);
                return parentFqn;
            }
        } else {
            // Class scope: Outer or Outer$Inner -> parent = pkg.module.Outer
            String parentFqn = packageName + "." + normalized(scopeChain);
            log.trace(
                    "Python parent lookup: scopeChain='{}', packageName='{}', normalized='{}', returning '{}' (class parent)",
                    scopeChain,
                    packageName,
                    normalized(scopeChain),
                    parentFqn);
            return parentFqn;
        }
    }

    // isClassLike is now implemented in the base class using LanguageSyntaxProfile.
    // buildClassMemberSkeletons is no longer directly called for parent skeleton string generation.

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return PY_SYNTAX_PROFILE;
    }

    @Override
    protected boolean requiresSemicolons() {
        return false;
    }

    /**
     * Include functions as class-like parents to detect local classes inside functions.
     */
    @Override
    protected boolean isClassLike(@Nullable TSNode node) {
        if (node == null) return false;
        return super.isClassLike(node) || FUNCTION_DEFINITION.equals(node.getType());
    }

    @Override
    protected List<String> extractRawSupertypesForClassLike(
            CodeUnit cu, @Nullable TSNode classNode, String signature, SourceContent sourceContent) {
        if (classNode == null) return List.of();
        // Extract superclass names from Python class definition
        // Pattern: class Child(Parent1, Parent2): ...
        return withCachedQuery(
                QueryType.DEFINITIONS,
                query -> {
                    // Use the actual definition node for range matching.
                    // If classNode is a decorated_definition, we must find the inner class_definition node
                    // to match the 'type.decl' capture in python.scm.
                    TSNode matchNode = classNode;
                    if (DECORATED_DEFINITION.equals(classNode.getType())) {
                        for (TSNode child : classNode.getNamedChildren()) {
                            if (CLASS_DEFINITION.equals(child.getType())) {
                                matchNode = child;
                                break;
                            }
                        }
                    }

                    // Ascend to root node for matching
                    TSNode root = classNode;
                    TSNode parent = root.getParent();
                    while (parent != null) {
                        root = parent;
                        parent = root.getParent();
                    }

                    try (TSQueryCursor cursor = new TSQueryCursor()) {
                        List<TSNode> aggregateSuperNodes = new ArrayList<>();
                        cursor.exec(query, root, sourceContent.text());

                        var match = new TSQueryMatch();
                        final TSNode finalMatchNode = matchNode;
                        final int targetStart = finalMatchNode.getStartByte();
                        final int targetEnd = finalMatchNode.getEndByte();

                        while (cursor.nextMatch(match)) {
                            TSNode declNode = null;
                            List<TSNode> superCapturesThisMatch = new ArrayList<>();

                            for (var cap : match.getCaptures()) {
                                var capName = query.getCaptureNameForId(cap.getIndex());
                                var n = cap.getNode();
                                if (n == null) continue;

                                if ("type.decl".equals(capName)) {
                                    declNode = n;
                                } else if ("type.super".equals(capName)) {
                                    superCapturesThisMatch.add(n);
                                }
                            }

                            if (declNode != null
                                    && declNode.getStartByte() == targetStart
                                    && declNode.getEndByte() == targetEnd) {
                                aggregateSuperNodes.addAll(superCapturesThisMatch);
                            }
                        }

                        // Sort by position to preserve source order
                        aggregateSuperNodes.sort(Comparator.comparingInt(TSNode::getStartByte));

                        List<String> supers = new ArrayList<>(aggregateSuperNodes.size());
                        for (var s : aggregateSuperNodes) {
                            var text = sourceContent.substringFrom(s).strip();
                            if (!text.isEmpty()) {
                                supers.add(text);
                            }
                        }

                        // Deduplicate while preserving order
                        var unique = new LinkedHashSet<>(supers);
                        return List.copyOf(unique);
                    }
                },
                List.of());
    }

    /**
     * Resolves a relative import to an absolute package path.
     *
     * @param file The file containing the import
     * @param relativeImportText The text of the relative_import node (e.g., ".sibling", "..parent", "...")
     * @return The absolute package path, or empty if resolution fails
     */
    private Optional<String> resolveRelativeImport(ProjectFile file, String relativeImportText) {
        // Count leading dots
        int dotCount = 0;
        while (dotCount < relativeImportText.length() && relativeImportText.charAt(dotCount) == '.') {
            dotCount++;
        }

        // Get the module name after the dots (if any)
        String relativeModule = relativeImportText.substring(dotCount);

        // Get the current file's package
        String currentPackage = getPackageNameForFile(file);

        // Navigate up dotCount-1 levels (1 dot = current package, 2 dots = parent, etc.)
        String[] packageParts = currentPackage.isEmpty() ? new String[0] : currentPackage.split("\\.");
        int levelsUp = dotCount - 1;

        if (levelsUp > packageParts.length) {
            // Import goes above project root - invalid
            log.warn("Relative import {} in {} goes above project root", relativeImportText, file.getRelPath());
            return Optional.empty();
        }

        // Build target package
        String[] targetParts = new String[packageParts.length - levelsUp];
        System.arraycopy(packageParts, 0, targetParts, 0, targetParts.length);
        String targetPackage = String.join(".", targetParts);

        // Append the relative module name if present
        if (!relativeModule.isEmpty()) {
            if (!targetPackage.isEmpty()) {
                targetPackage = targetPackage + "." + relativeModule;
            } else {
                targetPackage = relativeModule;
            }
        }

        return Optional.of(targetPackage);
    }

    /**
     * Resolves a module path to a ProjectFile, checking both module.py and package __init__.py.
     *
     * @param modulePath The dotted module path (e.g., "pkg.subpkg" or "module")
     * @return The resolved ProjectFile, or null if neither exists
     */
    private @Nullable ProjectFile resolveModuleFile(String modulePath) {
        var basePath = modulePath.replace('.', '/');

        // Try module.py first (only if modulePath is not empty, e.g. "pkg.mod")
        if (!basePath.isEmpty()) {
            var moduleFilePath = basePath + ".py";
            var moduleFile = new ProjectFile(getProject().getRoot(), moduleFilePath);
            if (Files.exists(moduleFile.absPath())) {
                return moduleFile;
            }
        }

        // Fall back to package __init__.py
        // If basePath is empty, result is "__init__.py". If not, "path/to/__init__.py"
        var initFilePath = basePath.isEmpty() ? "__init__.py" : basePath + "/__init__.py";
        var initFile = new ProjectFile(getProject().getRoot(), initFilePath);
        if (Files.exists(initFile.absPath())) {
            return initFile;
        }

        return null;
    }

    /**
     * Resolves import statements into a set of {@link CodeUnit}s, matching Python's native import semantics.
     * In Python, imports are executed in order and later imports override earlier ones with the same name.
     * This means a wildcard import that comes after an explicit import will shadow the explicit import
     * if both provide the same name.
     * <p>
     * Wildcard imports include public classes and functions (those without leading underscore).
     */
    // TODO: Performance optimization opportunity - This method re-parses each import line with
    // TreeSitter, even though the full AST was available during analyzeFileContent. A cleaner
    // approach would collect structured ImportInfo during the initial pass (while processing
    // import_statement/import_from_statement nodes) and store it in FileProperties. This would
    // eliminate redundant parsing. However, TreeSitter parsing is fast (~microseconds per line)
    // and Python files typically have few imports, so this is low priority unless profiling
    // shows it's a bottleneck.
    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        // Use a map to track resolved names - later imports overwrite earlier ones (Python semantics)
        Map<String, CodeUnit> resolvedByName = new LinkedHashMap<>();

        for (String importLine : importStatements) {
            if (importLine.isBlank()) continue;

            // Re-parse the import statement with TreeSitter (see TODO above)
            var parser = getTSParser();
            try (var tree = parser.parseStringOrThrow(null, importLine)) {
                var rootNode = tree.getRootNode();
                if (rootNode == null) continue;
                SourceContent importSc = SourceContent.of(importLine);
                withCachedQuery(QueryType.IMPORTS, query -> {
                    try (var cursor = new TSQueryCursor()) {
                        cursor.exec(query, rootNode, importSc.text());

                        var match = new TSQueryMatch();
                        String currentModule = null;
                        String wildcardModule = null;

                        // Collect all captures from this import statement
                        while (cursor.nextMatch(match)) {
                            // Reset per-match state
                            currentModule = null;
                            wildcardModule = null;

                            for (var cap : match.getCaptures()) {
                                var capName = query.getCaptureNameForId(cap.getIndex());
                                var node = cap.getNode();
                                if (node == null) continue;

                                var text = importSc.substringFrom(node);

                                switch (capName) {
                                    case IMPORT_MODULE -> currentModule = text;
                                    case IMPORT_RELATIVE -> {
                                        // Resolve relative import to absolute package path
                                        var absolutePath = resolveRelativeImport(file, text);
                                        currentModule = absolutePath.orElse(null);
                                    }
                                    case IMPORT_MODULE_WILDCARD -> wildcardModule = text;
                                    case IMPORT_RELATIVE_WILDCARD -> {
                                        // Resolve relative wildcard import to absolute package path
                                        var absolutePath = resolveRelativeImport(file, text);
                                        wildcardModule = absolutePath.orElse(null);
                                    }
                                    case IMPORT_WILDCARD -> {
                                        // Wildcard import - expand and add all public symbols (may overwrite
                                        // previous imports)
                                        if (wildcardModule != null && !wildcardModule.isEmpty()) {
                                            var moduleFile = resolveModuleFile(wildcardModule);
                                            if (moduleFile != null) {
                                                var decls = getDeclarations(moduleFile);
                                                for (CodeUnit child : decls) {
                                                    // Import public classes and functions (no underscore prefix)
                                                    if ((child.isClass() || child.isFunction())
                                                            && !child.identifier()
                                                                    .startsWith("_")) {
                                                        resolvedByName.put(child.identifier(), child);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    case IMPORT_NAME -> {
                                        if (currentModule != null) {
                                            // from X import Y
                                            var moduleFile = resolveModuleFile(currentModule);
                                            if (moduleFile != null) {
                                                var decls = getDeclarations(moduleFile);
                                                decls.stream()
                                                        .filter(cu ->
                                                                cu.identifier().equals(text)
                                                                        && (cu.isClass() || cu.isFunction()))
                                                        .findFirst()
                                                        .ifPresent(cu -> resolvedByName.put(cu.identifier(), cu));
                                            }
                                        } else if (wildcardModule == null) {
                                            // import X
                                            var definitions = getDefinitions(text);
                                            definitions.stream()
                                                    .filter(cu -> cu.isClass() || cu.isFunction())
                                                    .findFirst()
                                                    .ifPresent(cu -> resolvedByName.put(cu.identifier(), cu));
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
            } catch (Exception e) {
                log.warn("Error parsing import statement: {}", importLine, e);
            }
        }

        return Collections.unmodifiableSet(new LinkedHashSet<>(resolvedByName.values()));
    }

    @Override
    protected FileAnalysisAccumulator createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            FileAnalysisAccumulator acc) {

        if (modulePackageName.isBlank()) {
            return acc;
        }

        int idx = modulePackageName.lastIndexOf('.');
        String parentPkg = idx >= 0 ? modulePackageName.substring(0, idx) : "";
        String simpleName = idx >= 0 ? modulePackageName.substring(idx + 1) : modulePackageName;

        CodeUnit moduleCu = CodeUnit.module(file, parentPkg, simpleName);

        // If the module CodeUnit already exists in context (e.g. from another file in the same package),
        // we should still associate this file's TLDs with it.
        CodeUnit existing = acc.getByFqName(moduleCu.fqName());
        CodeUnit targetCu = (existing != null && existing.isModule()) ? existing : moduleCu;

        if (existing == null) {
            acc.addLookupKey(targetCu.fqName(), targetCu);
        }

        acc.addSignature(targetCu, "# module " + modulePackageName)
                .setHasBody(targetCu, true)
                .addSymbolIndex(targetCu.identifier(), targetCu)
                .addSymbolIndex(targetCu.shortName(), targetCu);

        List<CodeUnit> children = acc.topLevelCUs().stream()
                .filter(cu -> modulePackageName.equals(cu.packageName()))
                .filter(cu -> !cu.isModule())
                .toList();

        for (CodeUnit child : children) {
            acc.addChild(targetCu, child);
        }
        return acc;
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return performImportedCodeUnitsOf(file);
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return performReferencingFilesOf(file);
    }

    @Override
    public List<CodeUnit> getDirectAncestors(CodeUnit cu) {
        return performGetDirectAncestors(cu);
    }

    @Override
    public Set<CodeUnit> getDirectDescendants(CodeUnit cu) {
        return performGetDirectDescendants(cu);
    }

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        TSNode importNode = capturedNodesForMatch.get(IMPORT_DECLARATION);
        if (importNode == null) {
            return;
        }

        String importText = sourceContent.substringFrom(importNode).strip();
        if (importText.isEmpty()) {
            return;
        }

        // Check for wildcard patterns
        boolean isWildcard = capturedNodesForMatch.containsKey(IMPORT_WILDCARD)
                || capturedNodesForMatch.containsKey(IMPORT_MODULE_WILDCARD)
                || capturedNodesForMatch.containsKey(IMPORT_RELATIVE_WILDCARD);

        String identifier = null;
        String alias = null;

        // Check for alias first - if present, it becomes both the alias and the identifier used in code
        TSNode aliasNode = capturedNodesForMatch.get(IMPORT_ALIAS);
        if (aliasNode != null) {
            alias = sourceContent.substringFrom(aliasNode).strip();
            identifier = alias;
        } else {
            // Check for import.name - this is the imported symbol (e.g., "Foo" from "from pkg import Foo")
            TSNode nameNode = capturedNodesForMatch.get(IMPORT_NAME);
            if (nameNode != null) {
                identifier = sourceContent.substringFrom(nameNode).strip();
            } else {
                // For "import module" style (import pkg.mod), check import.module or import.relative
                TSNode moduleNode = capturedNodesForMatch.get(IMPORT_MODULE);
                if (moduleNode == null) {
                    moduleNode = capturedNodesForMatch.get(IMPORT_RELATIVE);
                }

                if (moduleNode != null) {
                    String modulePath = sourceContent.substringFrom(moduleNode).strip();
                    // Strip leading dots for relative imports before finding first segment
                    String cleanPath = modulePath.replaceFirst("^\\.+", "");
                    int cleanDotIdx = cleanPath.indexOf('.');
                    identifier = cleanDotIdx != -1 ? cleanPath.substring(0, cleanDotIdx) : cleanPath;
                }
            }
        }

        localImportInfos.add(new ImportInfo(importText, isWildcard, identifier, alias));
    }

    @Override
    protected String extractPackageFromWildcard(String rawSnippet) {
        // Python: "from pkg.sub import *" -> "pkg.sub"
        // Python: "from ..pkg import *" -> "..pkg"
        var matcher = WILDCARD_IMPORT_PATTERN.matcher(rawSnippet);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return super.extractPackageFromWildcard(rawSnippet);
    }

    @Override
    public Set<String> relevantImportsFor(CodeUnit cu) {
        var sourceOpt = getSource(cu, false);
        if (sourceOpt.isEmpty()) {
            return Set.of();
        }

        Set<String> extractedIdentifiers = extractTypeIdentifiers(sourceOpt.get());
        if (extractedIdentifiers.isEmpty()) {
            return Set.of();
        }

        List<ImportInfo> imports = importInfoOf(cu.source());
        if (imports.isEmpty()) {
            return Set.of();
        }

        Set<String> matchedImports = new LinkedHashSet<>();
        Set<String> resolvedIdentifiers = new HashSet<>();
        List<ImportInfo> wildcardImports = new ArrayList<>();

        // First pass: match explicit (non-wildcard) imports
        for (ImportInfo info : imports) {
            if (info.isWildcard()) {
                wildcardImports.add(info);
                continue;
            }

            String identifier = info.identifier();
            String alias = info.alias();

            if (identifier != null && extractedIdentifiers.contains(identifier)) {
                matchedImports.add(info.rawSnippet());
                resolvedIdentifiers.add(identifier);
            }
            if (alias != null && extractedIdentifiers.contains(alias)) {
                matchedImports.add(info.rawSnippet());
                resolvedIdentifiers.add(alias);
            }
        }

        // Determine unresolved identifiers
        Set<String> unresolvedIdentifiers = new HashSet<>(extractedIdentifiers);
        unresolvedIdentifiers.removeAll(resolvedIdentifiers);

        // Second pass: check wildcard imports for unresolved identifiers
        if (!unresolvedIdentifiers.isEmpty() && !wildcardImports.isEmpty()) {
            Set<String> resolvedViaWildcard = new HashSet<>();
            Set<ImportInfo> usedWildcards = new LinkedHashSet<>();

            for (String id : unresolvedIdentifiers) {
                for (ImportInfo wildcard : wildcardImports) {
                    String packageName = extractPackageFromWildcard(wildcard.rawSnippet());
                    if (packageName.isEmpty()) continue;

                    var definitions = getDefinitions(packageName + "." + id);
                    if (!definitions.isEmpty()) {
                        usedWildcards.add(wildcard);
                        resolvedViaWildcard.add(id);
                    }
                }
            }

            // Add wildcards that resolved identifiers
            for (ImportInfo wildcard : usedWildcards) {
                matchedImports.add(wildcard.rawSnippet());
            }

            // If any identifiers remain unresolved, include all wildcards as fallback
            unresolvedIdentifiers.removeAll(resolvedViaWildcard);
            if (!unresolvedIdentifiers.isEmpty()) {
                for (ImportInfo wildcard : wildcardImports) {
                    matchedImports.add(wildcard.rawSnippet());
                }
            }
        }

        return Collections.unmodifiableSet(matchedImports);
    }

    /**
     * Extracts identifiers from Python source using Tree-Sitter.
     * <p>
     * Trade-off: High Recall. Python lacks a distinct 'type_identifier' node type. We capture
     * all identifiers via AST traversal, which is more precise than regex because it naturally
     * excludes identifiers inside comments and string literals. While this may over-match local
     * variables, it ensures we don't miss any imported symbols used as types, decorators, or
     * function calls. The import filtering logic handles false positives gracefully.
     */
    @Override
    public Set<String> extractTypeIdentifiers(String source) {
        Set<String> identifiers = new HashSet<>();
        try (TSTree tree = getTSParser().parseStringOrThrow(null, source)) {
            TSNode rootNode = tree.getRootNode();
            if (rootNode == null) return identifiers;
            SourceContent sc = SourceContent.of(source);
            withCachedQuery(
                    QueryType.IDENTIFIERS,
                    query -> {
                        try (TSQueryCursor cursor = new TSQueryCursor()) {
                            cursor.exec(query, rootNode, sc.text());

                            TSQueryMatch match = new TSQueryMatch();
                            while (cursor.nextMatch(match)) {
                                for (TSQueryCapture capture : match.getCaptures()) {
                                    TSNode node = capture.getNode();
                                    if (node != null) {
                                        String text = sc.substringFrom(node).strip();
                                        if (!text.isEmpty()) {
                                            identifiers.add(text);
                                        }
                                    }
                                }
                            }
                        }
                        return true;
                    },
                    false);

            return identifiers;
        } catch (Exception e) {
            log.warn("Failed to parse ad-hoc source string: {}", e.getMessage());
            return identifiers;
        }
    }

    @Override
    public boolean couldImportFile(ProjectFile sourceFile, List<ImportInfo> imports, ProjectFile target) {
        PythonModuleInfo targetModule = resolveModuleInfo(target);
        String targetFqn = targetModule.moduleQualifiedPackage();

        for (ImportInfo imp : imports) {
            String raw = imp.rawSnippet();

            // Extract the module part.
            // Patterns:
            // 1. "import X.Y" -> module path is X.Y
            // 2. "from X.Y import Z" -> module path is X.Y
            // 3. "from .X import Y" -> relative module path
            // 4. "from . import Y" -> relative module path (dots only)

            String modulePath = null;
            if (raw.startsWith("from ")) {
                // "from path import name"
                int importIdx = raw.indexOf(" import ");
                if (importIdx != -1) {
                    modulePath = raw.substring(5, importIdx).trim();
                }
            } else if (raw.startsWith("import ")) {
                // "import path" or "import path as alias"
                String pathPart = raw.substring(7).trim();
                int asIdx = pathPart.indexOf(" as ");
                modulePath = (asIdx != -1) ? pathPart.substring(0, asIdx).trim() : pathPart;
            }

            if (modulePath == null || modulePath.isEmpty()) {
                continue;
            }

            // Handle relative imports
            String resolvedPath = modulePath;
            if (modulePath.startsWith(".")) {
                Optional<String> absolutePath = resolveRelativeImport(sourceFile, modulePath);
                if (absolutePath.isEmpty()) {
                    // Conservative: if we can't resolve the relative path, assume it might match.
                    return true;
                }
                resolvedPath = absolutePath.get();
            }

            // Check for potential dependencies based on module paths.
            // A dependency exists if:
            // 1. Exact match: The import targets the file directly (e.g., import mypkg.mod)
            // 2. Target is within the imported module: The import targets a package containing the file
            //    (e.g., 'import mypkg' where the target file is 'mypkg/mod.py').
            // 3. Import is from within the target module: The import targets a sub-module or member of the file
            //    (e.g., 'from mypkg.mod import func').
            if (targetFqn.equals(resolvedPath)
                    || targetFqn.startsWith(resolvedPath + ".")
                    || resolvedPath.startsWith(targetFqn + ".")) {
                return true;
            }

            // Also check if the imported identifier matches the target's module name
            // (e.g. "from mypackage import utils" where target is mypackage/utils.py)
            if (imp.identifier() != null) {
                String fullImportedName = resolvedPath + "." + imp.identifier();
                // Check if this full name exactly matches the target or is a parent of the target
                if (targetFqn.equals(fullImportedName) || targetFqn.startsWith(fullImportedName + ".")) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return "__init__".equals(candidate.identifier());
    }

    @Override
    public List<CodeUnit> computeSupertypes(CodeUnit cu) {
        if (!cu.isClass()) return List.of();

        // Get raw supertype names lazily
        var rawNames = getRawSupertypesLazily(cu);

        if (rawNames.isEmpty()) {
            return List.of();
        }

        // Get resolved imports for this file
        Set<CodeUnit> resolvedImports = importedCodeUnitsOf(cu.source());

        List<CodeUnit> result = new ArrayList<>();

        for (String rawName : rawNames) {
            // First try to find in imports
            Optional<CodeUnit> fromImport = resolvedImports.stream()
                    .filter(imp -> imp.identifier().equals(rawName))
                    .findFirst();

            if (fromImport.isPresent()) {
                result.add(fromImport.get());
                continue;
            }

            // Then try same package (same file or same directory)
            String packageName = cu.packageName();
            String fqnInPackage = packageName.isEmpty() ? rawName : packageName + "." + rawName;
            var inPackageSet = getDefinitions(fqnInPackage);
            var inPackage = inPackageSet.stream().filter(CodeUnit::isClass).findFirst();
            if (inPackage.isPresent()) {
                result.add(inPackage.get());
                continue;
            }

            // Try global search
            var searchResults = searchDefinitions(rawName, false);
            Optional<CodeUnit> fromSearch =
                    searchResults.stream().filter(CodeUnit::isClass).findFirst();
            fromSearch.ifPresent(result::add);
        }

        return result;
    }
}
