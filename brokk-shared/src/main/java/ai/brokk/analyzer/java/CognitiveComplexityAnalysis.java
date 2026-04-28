package ai.brokk.analyzer.java;

import static ai.brokk.analyzer.java.Constants.nodeType;
import static org.treesitter.JavaNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_STATEMENT)),
            Set.of(),
            Set.of(
                    nodeType(FOR_STATEMENT),
                    nodeType(ENHANCED_FOR_STATEMENT),
                    nodeType(WHILE_STATEMENT),
                    nodeType(DO_STATEMENT)),
            Set.of(nodeType(CATCH_CLAUSE)),
            Set.of(nodeType(TERNARY_EXPRESSION)),
            Set.of(nodeType(SWITCH_LABEL), nodeType(SWITCH_RULE)),
            Set.of(),
            Set.of(nodeType(BINARY_EXPRESSION)),
            Set.of("&&", "||"),
            Set.of(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT)),
            Set.of(),
            Set.of(nodeType(LAMBDA_EXPRESSION)),
            Set.of(),
            CognitiveComplexityAnalysis::isDefaultSwitchLabel,
            node -> false);

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }

    private static boolean isDefaultSwitchLabel(TSNode node, SourceContent sourceContent) {
        return sourceContent.substringFrom(node).strip().startsWith("default");
    }
}
