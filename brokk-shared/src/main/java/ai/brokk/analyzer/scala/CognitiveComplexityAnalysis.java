package ai.brokk.analyzer.scala;

import static ai.brokk.analyzer.scala.Constants.nodeType;
import static org.treesitter.ScalaNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_EXPRESSION)),
            Set.of(),
            Set.of(nodeType(FOR_EXPRESSION), nodeType(WHILE_EXPRESSION), nodeType(DO_WHILE_EXPRESSION)),
            Set.of(),
            Set.of(),
            Set.of(nodeType(CASE_CLAUSE)),
            Set.of(),
            Set.of(nodeType(INFIX_EXPRESSION)),
            Set.of("&&", "||"),
            Set.of("break_expression", "continue_expression"),
            Set.of(nodeType(FUNCTION_DEFINITION)),
            Set.of(nodeType(LAMBDA_EXPRESSION)),
            Set.of("else_clause"),
            CognitiveComplexitySupport::isWildcardCase,
            node -> false);

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
