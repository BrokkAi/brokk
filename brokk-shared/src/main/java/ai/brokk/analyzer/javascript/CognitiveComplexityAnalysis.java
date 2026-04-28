package ai.brokk.analyzer.javascript;

import static ai.brokk.analyzer.ASTTraversalUtils.sameRange;
import static ai.brokk.analyzer.ASTTraversalUtils.typeOf;
import static ai.brokk.analyzer.javascript.Constants.nodeField;
import static ai.brokk.analyzer.javascript.Constants.nodeType;
import static java.util.Objects.requireNonNull;
import static org.treesitter.TsxNodeType.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;
import org.treesitter.TsxNodeField;
import org.treesitter.TsxNodeType;

public final class CognitiveComplexityAnalysis {

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root) {
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

            var nodeType = TsxNodeType.fromType(type);
            switch (nodeType) {
                case IF_STATEMENT -> {
                    complexity += frame.elseIfContinuation() ? 1 : controlFlowIncrement(frame.nesting());
                    TSNode alternative = node.getChildByFieldName(nodeField(TsxNodeField.ALTERNATIVE));
                    pushAlternative(work, alternative, frame.nesting());
                    pushNamedChildrenExcept(work, node, alternative, frame.nesting() + 1);
                }
                case FOR_STATEMENT,
                        FOR_IN_STATEMENT,
                        WHILE_STATEMENT,
                        DO_STATEMENT,
                        CATCH_CLAUSE,
                        TERNARY_EXPRESSION -> {
                    complexity += controlFlowIncrement(frame.nesting());
                    pushNamedChildren(work, node, frame.nesting() + 1);
                }
                case SWITCH_CASE -> {
                    complexity += controlFlowIncrement(frame.nesting());
                    pushNamedChildren(work, node, frame.nesting() + 1);
                }
                case SWITCH_DEFAULT -> pushNamedChildren(work, node, frame.nesting());
                case BINARY_EXPRESSION -> {
                    if (!isNestedBinaryExpression(node)) {
                        complexity += logicalOperatorSequenceCount(node);
                    }
                    pushNamedChildren(work, node, frame.nesting());
                }
                case BREAK_STATEMENT, CONTINUE_STATEMENT -> {
                    if (isLabeledJump(node)) {
                        complexity++;
                    }
                    pushNamedChildren(work, node, frame.nesting());
                }
                default -> {
                    if (!frame.root() && isFunctionBoundary(node)) {
                        continue;
                    }
                    pushNamedChildren(work, node, frame.nesting(), frame.root() && !isFunctionBoundary(node));
                }
            }
        }
        return complexity;
    }

    private static void pushAlternative(ArrayDeque<CognitiveFrame> work, @Nullable TSNode alternative, int nesting) {
        String alternativeType = typeOf(alternative);
        if (alternativeType == null) {
            return;
        }
        TSNode alternativeNode = requireNonNull(alternative);
        if (nodeType(ELSE_CLAUSE).equals(alternativeType)) {
            TSNode elseIf = directIfChild(alternativeNode);
            if (elseIf != null) {
                work.push(new CognitiveFrame(elseIf, nesting, true, false));
                pushNamedChildrenExcept(work, alternativeNode, elseIf, nesting + 1);
                return;
            }
        }
        if (nodeType(IF_STATEMENT).equals(alternativeType)) {
            work.push(new CognitiveFrame(alternativeNode, nesting, true, false));
        } else {
            work.push(new CognitiveFrame(alternativeNode, nesting + 1, false, false));
        }
    }

    private static @Nullable TSNode directIfChild(TSNode node) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (nodeType(IF_STATEMENT).equals(typeOf(child))) {
                return child;
            }
        }
        return null;
    }

    private static void pushNamedChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting) {
        pushNamedChildren(work, node, nesting, false);
    }

    private static void pushNamedChildren(
            ArrayDeque<CognitiveFrame> work, TSNode node, int nesting, boolean rootChildren) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            if (child != null && typeOf(child) != null) {
                work.push(new CognitiveFrame(child, nesting, false, rootChildren));
            }
        }
    }

    private static void pushNamedChildrenExcept(
            ArrayDeque<CognitiveFrame> work, TSNode node, @Nullable TSNode except, int nesting) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            if (child == null || typeOf(child) == null) {
                continue;
            }
            if (except != null && sameRange(child, except)) {
                continue;
            }
            work.push(new CognitiveFrame(child, nesting, false, false));
        }
    }

    private static int controlFlowIncrement(int nesting) {
        return 1 + nesting;
    }

    private static boolean isNestedBinaryExpression(TSNode node) {
        TSNode parent = node.getParent();
        return nodeType(BINARY_EXPRESSION).equals(typeOf(parent));
    }

    private static boolean isLabeledJump(TSNode node) {
        return node.getNamedChildCount() > 0;
    }

    private static boolean isFunctionBoundary(TSNode node) {
        return switch (TsxNodeType.fromType(typeOf(node))) {
            case FUNCTION_DECLARATION,
                    FUNCTION_EXPRESSION,
                    GENERATOR_FUNCTION,
                    GENERATOR_FUNCTION_DECLARATION,
                    METHOD_DEFINITION,
                    ARROW_FUNCTION -> true;
            default -> false;
        };
    }

    private static int logicalOperatorSequenceCount(TSNode node) {
        var operators = new ArrayList<String>();
        var work = new ArrayDeque<TSNode>();
        work.push(node);
        while (!work.isEmpty()) {
            TSNode current = work.pop();
            for (int i = current.getChildCount() - 1; i >= 0; i--) {
                TSNode child = current.getChild(i);
                String type = typeOf(child);
                if (type == null) {
                    continue;
                }
                if (isLogicalOperator(type)) {
                    operators.add(type);
                } else if (child != null && nodeType(BINARY_EXPRESSION).equals(type)) {
                    work.push(child);
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

    private static boolean isLogicalOperator(@Nullable String type) {
        return "&&".equals(type) || "||".equals(type) || "??".equals(type);
    }

    private record CognitiveFrame(TSNode node, int nesting, boolean elseIfContinuation, boolean root) {}
}
