package ai.brokk.analyzer.java;

import static ai.brokk.analyzer.java.Constants.nodeType;
import static org.treesitter.JavaNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = CognitiveComplexitySupport.config()
            .ifTypes(nodeType(IF_STATEMENT))
            .loopTypes(
                    nodeType(FOR_STATEMENT),
                    nodeType(ENHANCED_FOR_STATEMENT),
                    nodeType(WHILE_STATEMENT),
                    nodeType(DO_STATEMENT))
            .catchTypes(nodeType(CATCH_CLAUSE))
            .conditionalTypes(nodeType(TERNARY_EXPRESSION))
            .caseTypes(nodeType(SWITCH_LABEL), nodeType(SWITCH_RULE))
            .binaryTypes(nodeType(BINARY_EXPRESSION))
            .logicalOperators("&&", "||")
            .jumpTypes(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT))
            .anonymousFunctionTypes(nodeType(LAMBDA_EXPRESSION))
            .defaultCasePredicate(CognitiveComplexityAnalysis::isDefaultSwitchLabel)
            .build();

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }

    private static boolean isDefaultSwitchLabel(TSNode node, SourceContent sourceContent) {
        return sourceContent.substringFrom(node).strip().startsWith("default");
    }
}
