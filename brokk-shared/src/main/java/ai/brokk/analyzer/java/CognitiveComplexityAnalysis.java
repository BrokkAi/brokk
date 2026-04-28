package ai.brokk.analyzer.java;

import static ai.brokk.analyzer.java.Constants.nodeField;
import static ai.brokk.analyzer.java.Constants.nodeType;
import static org.treesitter.JavaNodeType.*;

import ai.brokk.analyzer.SourceContent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import org.jetbrains.annotations.Nullable;
import org.treesitter.JavaNodeField;
import org.treesitter.JavaNodeType;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        int complexity = 0;
        var work = new ArrayDeque<CognitiveFrame>();
        work.push(new CognitiveFrame(root, 0, false));

        while (!work.isEmpty()) {
            var frame = work.pop();
            TSNode node = frame.node();
            String type = node.getType();
            if (type == null) {
                continue;
            }

            var nodeType = JavaNodeType.fromType(type);
            switch (nodeType) {
                case IF_STATEMENT -> {
                    complexity += frame.elseIfContinuation() ? 1 : controlFlowIncrement(frame.nesting());
                    TSNode alternative = node.getChildByFieldName(nodeField(JavaNodeField.ALTERNATIVE));
                    pushAlternative(work, alternative, frame.nesting());
                    pushNamedChildrenExcept(work, node, alternative, frame.nesting() + 1);
                }
                case FOR_STATEMENT,
                        ENHANCED_FOR_STATEMENT,
                        WHILE_STATEMENT,
                        DO_STATEMENT,
                        CATCH_CLAUSE,
                        TERNARY_EXPRESSION -> {
                    complexity += controlFlowIncrement(frame.nesting());
                    pushNamedChildren(work, node, frame.nesting() + 1);
                }
                case SWITCH_LABEL, SWITCH_RULE -> {
                    if (!isDefaultSwitchLabel(node, sourceContent)) {
                        complexity += controlFlowIncrement(frame.nesting());
                        pushNamedChildren(work, node, frame.nesting() + 1);
                    } else {
                        pushNamedChildren(work, node, frame.nesting());
                    }
                }
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
                    int childNesting = nodeType(LAMBDA_EXPRESSION).equals(type) ? frame.nesting() + 1 : frame.nesting();
                    pushNamedChildren(work, node, childNesting);
                }
            }
        }
        return complexity;
    }

    private static void pushAlternative(ArrayDeque<CognitiveFrame> work, @Nullable TSNode alternative, int nesting) {
        if (alternative == null) {
            return;
        }
        if (nodeType(IF_STATEMENT).equals(alternative.getType())) {
            work.push(new CognitiveFrame(alternative, nesting, true));
        } else {
            work.push(new CognitiveFrame(alternative, nesting + 1, false));
        }
    }

    private static void pushNamedChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            if (child != null) {
                work.push(new CognitiveFrame(child, nesting, false));
            }
        }
    }

    private static void pushNamedChildrenExcept(
            ArrayDeque<CognitiveFrame> work, TSNode node, @Nullable TSNode except, int nesting) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            if (child == null) {
                continue;
            }
            if (except != null && sameNode(child, except)) {
                continue;
            }
            work.push(new CognitiveFrame(child, nesting, false));
        }
    }

    private static int controlFlowIncrement(int nesting) {
        return 1 + nesting;
    }

    private static boolean isDefaultSwitchLabel(TSNode node, SourceContent sourceContent) {
        return sourceContent.substringFrom(node).strip().startsWith("default");
    }

    private static boolean isNestedBinaryExpression(TSNode node) {
        TSNode parent = node.getParent();
        return parent != null && nodeType(BINARY_EXPRESSION).equals(parent.getType());
    }

    private static boolean isLabeledJump(TSNode node) {
        return node.getNamedChildCount() > 0;
    }

    private static int logicalOperatorSequenceCount(TSNode node) {
        var operators = new ArrayList<String>();
        var work = new ArrayDeque<TSNode>();
        work.push(node);
        while (!work.isEmpty()) {
            TSNode current = work.pop();
            for (int i = current.getChildCount() - 1; i >= 0; i--) {
                TSNode child = current.getChild(i);
                if (child == null) {
                    continue;
                }
                String type = child.getType();
                if ("&&".equals(type) || "||".equals(type)) {
                    operators.add(type);
                } else if (nodeType(BINARY_EXPRESSION).equals(type)) {
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

    private static boolean sameNode(TSNode left, TSNode right) {
        return left.getStartByte() == right.getStartByte() && left.getEndByte() == right.getEndByte();
    }

    private record CognitiveFrame(TSNode node, int nesting, boolean elseIfContinuation) {}
}
