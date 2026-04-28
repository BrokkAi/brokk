package ai.brokk.analyzer.python;

import static ai.brokk.analyzer.python.Constants.nodeType;
import static org.treesitter.PythonNodeType.*;

import ai.brokk.analyzer.SourceContent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import org.treesitter.PythonNodeType;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        int complexity = 0;
        var work = new ArrayDeque<CognitiveFrame>();
        work.push(new CognitiveFrame(root, 0, false, true));

        while (!work.isEmpty()) {
            var frame = work.pop();
            TSNode node = frame.node();
            String type = node.getType();
            if (type == null) {
                continue;
            }

            var nodeType = PythonNodeType.fromType(type);
            switch (nodeType) {
                case IF_STATEMENT -> {
                    complexity += controlFlowIncrement(frame.nesting());
                    pushIfChildren(work, node, frame.nesting());
                }
                case ELIF_CLAUSE -> {
                    complexity += frame.elseIfContinuation() ? 1 : controlFlowIncrement(frame.nesting());
                    pushIfChildren(work, node, frame.nesting());
                }
                case FOR_STATEMENT, WHILE_STATEMENT, EXCEPT_CLAUSE, CONDITIONAL_EXPRESSION, CASE_CLAUSE -> {
                    complexity += controlFlowIncrement(frame.nesting());
                    pushNamedChildren(work, node, frame.nesting() + 1);
                }
                case BOOLEAN_OPERATOR -> {
                    if (!isNestedBooleanOperator(node)) {
                        complexity += logicalOperatorSequenceCount(node, sourceContent);
                    }
                    pushNamedChildren(work, node, frame.nesting());
                }
                default -> {
                    if (!frame.root() && isFunctionBoundary(node)) {
                        continue;
                    }
                    int childNesting = nodeType(LAMBDA).equals(type) ? frame.nesting() + 1 : frame.nesting();
                    pushNamedChildren(
                            work,
                            node,
                            childNesting,
                            frame.root() && nodeType(DECORATED_DEFINITION).equals(type));
                }
            }
        }
        return complexity;
    }

    private static void pushIfChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            if (child == null) {
                continue;
            }
            String type = child.getType();
            if (nodeType(ELIF_CLAUSE).equals(type)) {
                work.push(new CognitiveFrame(child, nesting, true, false));
            } else {
                work.push(new CognitiveFrame(child, nesting + 1, false, false));
            }
        }
    }

    private static void pushNamedChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting) {
        pushNamedChildren(work, node, nesting, false);
    }

    private static void pushNamedChildren(
            ArrayDeque<CognitiveFrame> work, TSNode node, int nesting, boolean rootChildren) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            if (child != null) {
                work.push(new CognitiveFrame(child, nesting, false, rootChildren));
            }
        }
    }

    private static int controlFlowIncrement(int nesting) {
        return 1 + nesting;
    }

    private static boolean isNestedBooleanOperator(TSNode node) {
        TSNode parent = node.getParent();
        return parent != null && nodeType(BOOLEAN_OPERATOR).equals(parent.getType());
    }

    private static boolean isFunctionBoundary(TSNode node) {
        String type = node.getType();
        if (nodeType(FUNCTION_DEFINITION).equals(type)) {
            return true;
        }
        if (!nodeType(DECORATED_DEFINITION).equals(type)) {
            return false;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null && nodeType(FUNCTION_DEFINITION).equals(child.getType())) {
                return true;
            }
        }
        return false;
    }

    private static int logicalOperatorSequenceCount(TSNode node, SourceContent sourceContent) {
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
                if (nodeType(BOOLEAN_OPERATOR).equals(type)) {
                    work.push(child);
                    continue;
                }
                if (isLogicalOperatorToken(child, sourceContent)) {
                    String token = sourceContent.substringFrom(child);
                    operators.add(token);
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

    private static boolean isLogicalOperatorToken(TSNode node, SourceContent sourceContent) {
        if (node.getChildCount() > 0) {
            return false;
        }
        int byteLength = node.getEndByte() - node.getStartByte();
        if (byteLength != 2 && byteLength != 3) {
            return false;
        }
        String token = sourceContent.substringFrom(node);
        return "and".equals(token) || "or".equals(token);
    }

    private record CognitiveFrame(TSNode node, int nesting, boolean elseIfContinuation, boolean root) {}
}
