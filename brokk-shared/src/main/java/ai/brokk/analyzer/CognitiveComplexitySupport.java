package ai.brokk.analyzer;

import static ai.brokk.analyzer.ASTTraversalUtils.sameRange;
import static ai.brokk.analyzer.ASTTraversalUtils.typeOf;
import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

public final class CognitiveComplexitySupport {

    private CognitiveComplexitySupport() {}

    public record Config(
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
            Set<String> elseClauseTypes) {}

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

            if (config.ifTypes().contains(type)) {
                complexity += frame.elseIfContinuation() ? 1 : controlFlowIncrement(frame.nesting());
                pushIfChildren(work, node, frame.nesting(), config);
            } else if (config.loopTypes().contains(type)
                    || config.catchTypes().contains(type)
                    || config.conditionalTypes().contains(type)) {
                complexity += controlFlowIncrement(frame.nesting());
                pushNamedChildren(work, node, frame.nesting() + 1);
            } else if (config.caseTypes().contains(type)) {
                if (!isWildcardCase(node, sourceContent)) {
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
                if (!frame.root() && config.namedFunctionBoundaryTypes().contains(type)) {
                    continue;
                }
                int childNesting =
                        config.anonymousFunctionTypes().contains(type) ? frame.nesting() + 1 : frame.nesting();
                pushNamedChildren(work, node, childNesting);
            }
        }
        return complexity;
    }

    private static void pushIfChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting, Config config) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            String type = typeOf(child);
            if (type == null) {
                continue;
            }
            TSNode childNode = requireNonNull(child);
            if (config.elseClauseTypes().contains(type)) {
                TSNode elseIf = directChildOfAnyType(childNode, config.ifTypes());
                if (elseIf != null) {
                    work.push(new CognitiveFrame(elseIf, nesting, true, false));
                    pushNamedChildrenExcept(work, childNode, elseIf, nesting + 1);
                } else {
                    work.push(new CognitiveFrame(childNode, nesting + 1, false, false));
                }
            } else if (config.ifTypes().contains(type)) {
                work.push(new CognitiveFrame(childNode, nesting, true, false));
            } else {
                work.push(new CognitiveFrame(childNode, nesting + 1, false, false));
            }
        }
    }

    private static @Nullable TSNode directChildOfAnyType(TSNode node, Set<String> types) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (types.contains(typeOf(child))) {
                return child;
            }
        }
        return null;
    }

    private static void pushNamedChildren(ArrayDeque<CognitiveFrame> work, TSNode node, int nesting) {
        for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getNamedChild(i);
            if (child != null && typeOf(child) != null) {
                work.push(new CognitiveFrame(child, nesting, false, false));
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
            for (int i = current.getChildCount() - 1; i >= 0; i--) {
                TSNode child = current.getChild(i);
                String type = typeOf(child);
                if (type == null) {
                    continue;
                }
                if (config.binaryTypes().contains(type)) {
                    work.push(requireNonNull(child));
                    continue;
                }
                if (config.logicalOperators().contains(type)) {
                    operators.add(type);
                    continue;
                }
                if (child != null && isLogicalOperatorToken(child, sourceContent, config.logicalOperators())) {
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

    private static boolean isWildcardCase(TSNode node, SourceContent sourceContent) {
        String text = sourceContent.substringFrom(node).stripLeading();
        return text.startsWith("_") || text.startsWith("case _ =>");
    }

    private record CognitiveFrame(TSNode node, int nesting, boolean elseIfContinuation, boolean root) {}
}
