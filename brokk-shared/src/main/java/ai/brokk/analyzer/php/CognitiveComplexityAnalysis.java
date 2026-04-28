package ai.brokk.analyzer.php;

import static ai.brokk.analyzer.php.Constants.nodeType;
import static org.treesitter.PhpNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_STATEMENT), nodeType(ELSE_IF_CLAUSE)),
            Set.of(
                    nodeType(FOR_STATEMENT),
                    nodeType(FOREACH_STATEMENT),
                    nodeType(WHILE_STATEMENT),
                    nodeType(DO_STATEMENT)),
            Set.of(nodeType(CATCH_CLAUSE)),
            Set.of(nodeType(CONDITIONAL_EXPRESSION)),
            Set.of(nodeType(CASE_STATEMENT), "match_condition"),
            Set.of(nodeType(DEFAULT_STATEMENT), nodeType(MATCH_DEFAULT_EXPRESSION)),
            Set.of(nodeType(BINARY_EXPRESSION)),
            Set.of("&&", "||", "and", "or", "??"),
            Set.of(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT)),
            Set.of(nodeType(FUNCTION_DEFINITION), nodeType(METHOD_DECLARATION)),
            Set.of(nodeType(ANONYMOUS_FUNCTION), nodeType(ARROW_FUNCTION)),
            Set.of(nodeType(ELSE_CLAUSE)));

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
