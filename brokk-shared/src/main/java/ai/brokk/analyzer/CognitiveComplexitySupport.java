package ai.brokk.analyzer;

import static ai.brokk.analyzer.ASTTraversalUtils.children;
import static ai.brokk.analyzer.ASTTraversalUtils.directNamedChildOfAnyType;
import static ai.brokk.analyzer.ASTTraversalUtils.namedChildren;
import static ai.brokk.analyzer.ASTTraversalUtils.sameRange;
import static ai.brokk.analyzer.ASTTraversalUtils.typeOf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

public final class CognitiveComplexitySupport {

    private CognitiveComplexitySupport() {}

    public record Config(
            Set<String> ifTypes,
            Set<String> alternateIfTypes,
            Set<String> loopTypes,
            Set<String> catchTypes,
            Set<String> conditionalTypes,
            Set<String> caseTypes,
            Set<String> defaultCaseTypes,
            Set<String> binaryTypes,
            Set<String> logicalOperators,
            Set<String> jumpTypes,
            Set<String> namedFunctionBoundaryTypes,
            Set<String> anonymousFunctionTypes,
            Set<String> elseClauseTypes,
            BiPredicate<TSNode, SourceContent> defaultCasePredicate,
            Predicate<TSNode> namedFunctionBoundaryPredicate) {
        public Config(
                Set<String> ifTypes,
                Set<String> loopTypes,
                Set<String> catchTypes,
                Set<String> conditionalTypes,
                Set<String> caseTypes,
                Set<String> defaultCaseTypes,
                Set<String> binaryTypes,
                Set<String> logicalOperators,
                Set<String> jumpTypes,
                Set<String> namedFunctionBoundaryTypes,
                Set<String> anonymousFunctionTypes,
                Set<String> elseClauseTypes) {
            this(
                    ifTypes,
                    Set.of(),
                    loopTypes,
                    catchTypes,
                    conditionalTypes,
                    caseTypes,
                    defaultCaseTypes,
                    binaryTypes,
                    logicalOperators,
                    jumpTypes,
                    namedFunctionBoundaryTypes,
                    anonymousFunctionTypes,
                    elseClauseTypes,
                    (node, sourceContent) -> false,
                    node -> false);
        }

        Set<String> allIfTypes() {
            var allTypes = new java.util.HashSet<>(ifTypes);
            allTypes.addAll(alternateIfTypes);
            return allTypes;
        }
    }

    public static int compute(TSNode root, SourceContent sourceContent, Config config) {
        int complexity = 0;
        var work = new ArrayDeque<CognitiveFrame>();
        work.push(new CognitiveFrame(root, 0, false, true));

        while (!work.isEmpty()) {
            var frame = work.pop();
            TSNode node = frame.node();
            String type = typeOf(node);
            if (type == null) {
                continue;
            }

            if (config.ifTypes().contains(type) || config.alternateIfTypes().contains(type)) {
                complexity += frame.elseIfContinuation() ? 1 : controlFlowIncrement(frame.nesting());
                pushIfChildren(work, node, frame.nesting(), config);
            } else if (config.loopTypes().contains(type)
                    || config.catchTypes().contains(type)
                    || config.conditionalTypes().contains(type)) {
                complexity += controlFlowIncrement(frame.nesting());
                pushNamedChildren(work, node, frame.nesting() + 1);
            } else if (config.caseTypes().contains(type)) {
                if (!config.defaultCasePredicate().test(node, sourceContent)) {
                    complexity += controlFlowIncrement(frame.nesting());
                }
                pushNamedChildren(work, node, frame.nesting() + 1);
            } else if (config.defaultCaseTypes().contains(type)) {
                pushNamedChildren(work, node, frame.nesting());
            } else if (config.binaryTypes().contains(type)) {
                if (!isNestedType(node, config.binaryTypes())) {
                    complexity += logicalOperatorSequenceCount(node, sourceContent, config);
                }
                pushNamedChildren(work, node, frame.nesting());
            } else if (config.jumpTypes().contains(type)) {
                if (isLabeledJump(node)) {
                    complexity++;
                }
                pushNamedChildren(work, node, frame.nesting());
            } else {
                boolean namedFunctionBoundary =
                        config.namedFunctionBoundaryTypes().contains(type)
                                || config.namedFunctionBoundaryPredicate().test(node);
                if (!frame.root() && namedFunctionBoundary) {
                    continue;
                }
                int childNesting =
                        config.anonymousFunctionTypes().contains(type) ? frame.nesting() + 1 : frame.nesting();
                pushNamedChildren(work, node, childNesting, frame.root() && !namedFunctionBoundary);
            }
        }
        return complexity;
    }

    private static void pushIfChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting, Config config) {
        var children = namedChildren(node);
        for (int i = children.size() - 1; i >= 0; i--) {
            TSNode child = children.get(i);
            String type = typeOf(child);
            if (config.elseClauseTypes().contains(type)) {
                TSNode elseIf = directNamedChildOfAnyType(child, config.allIfTypes());
                if (elseIf != null) {
                    work.push(new CognitiveFrame(elseIf, nesting, true, false));
                    pushNamedChildrenExcept(work, child, elseIf, nesting + 1);
                } else {
                    work.push(new CognitiveFrame(child, nesting + 1, false, false));
                }
            } else if (config.ifTypes().contains(type)
                    || config.alternateIfTypes().contains(type)) {
                work.push(new CognitiveFrame(child, nesting, true, false));
            } else {
                work.push(new CognitiveFrame(child, nesting + 1, false, false));
            }
        }
    }

    private static void pushNamedChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting) {
        pushNamedChildren(work, node, nesting, false);
    }

    private static void pushNamedChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting, boolean root) {
        var children = namedChildren(node);
        for (int i = children.size() - 1; i >= 0; i--) {
            work.push(new CognitiveFrame(children.get(i), nesting, false, root));
        }
    }

    private static void pushNamedChildrenExcept(
            ArrayDeque<CognitiveFrame> work, TSNode node, @Nullable TSNode except, int nesting) {
        var children = namedChildren(node);
        for (int i = children.size() - 1; i >= 0; i--) {
            TSNode child = children.get(i);
            if (except != null && sameRange(child, except)) {
                continue;
            }
            work.push(new CognitiveFrame(child, nesting, false, false));
        }
    }

    private static int controlFlowIncrement(int nesting) {
        return 1 + nesting;
    }

    private static boolean isNestedType(TSNode node, Set<String> types) {
        return types.contains(typeOf(node.getParent()));
    }

    private static boolean isLabeledJump(TSNode node) {
        return node.getNamedChildCount() > 0;
    }

    private static int logicalOperatorSequenceCount(TSNode node, SourceContent sourceContent, Config config) {
        var operators = new ArrayList<String>();
        var work = new ArrayDeque<TSNode>();
        work.push(node);
        while (!work.isEmpty()) {
            TSNode current = work.pop();
            var children = children(current);
            for (int i = children.size() - 1; i >= 0; i--) {
                TSNode child = children.get(i);
                String type = typeOf(child);
                if (config.binaryTypes().contains(type)) {
                    work.push(child);
                    continue;
                }
                if (config.logicalOperators().contains(type)) {
                    operators.add(type);
                    continue;
                }
                if (isLogicalOperatorToken(child, sourceContent, config.logicalOperators())) {
                    operators.add(sourceContent.substringFrom(child));
                }
            }
        }

        int sequences = 0;
        String previous = "";
        for (int i = operators.size() - 1; i >= 0; i--) {
            String operator = operators.get(i);
            if (!operator.equals(previous)) {
                sequences++;
                previous = operator;
            }
        }
        return sequences;
    }

    private static boolean isLogicalOperatorToken(TSNode node, SourceContent sourceContent, Set<String> operators) {
        if (node.getChildCount() > 0) {
            return false;
        }
        int byteLength = node.getEndByte() - node.getStartByte();
        if (byteLength < 2 || byteLength > 4) {
            return false;
        }
        return operators.contains(sourceContent.substringFrom(node));
    }

    public static boolean isWildcardCase(TSNode node, SourceContent sourceContent) {
        String text = sourceContent.substringFrom(node).stripLeading();
        return text.startsWith("_") || text.startsWith("case _ =>");
    }

    private record CognitiveFrame(TSNode node, int nesting, boolean elseIfContinuation, boolean root) {}
}
