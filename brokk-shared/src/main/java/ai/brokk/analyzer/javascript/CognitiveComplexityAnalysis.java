package ai.brokk.analyzer.javascript;

import static ai.brokk.analyzer.javascript.Constants.nodeType;
import static org.treesitter.TsxNodeType.*;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of(nodeType(IF_STATEMENT)),
            Set.of(
                    nodeType(FOR_STATEMENT),
                    nodeType(FOR_IN_STATEMENT),
                    nodeType(WHILE_STATEMENT),
                    nodeType(DO_STATEMENT)),
            Set.of(nodeType(CATCH_CLAUSE)),
            Set.of(nodeType(TERNARY_EXPRESSION)),
            Set.of(nodeType(SWITCH_CASE)),
            Set.of(nodeType(SWITCH_DEFAULT)),
            Set.of(nodeType(BINARY_EXPRESSION)),
            Set.of("&&", "||", "??"),
            Set.of(nodeType(BREAK_STATEMENT), nodeType(CONTINUE_STATEMENT)),
            Set.of(
                    nodeType(FUNCTION_DECLARATION),
                    nodeType(FUNCTION_EXPRESSION),
                    nodeType(GENERATOR_FUNCTION),
                    nodeType(GENERATOR_FUNCTION_DECLARATION),
                    nodeType(METHOD_DEFINITION),
                    nodeType(ARROW_FUNCTION)),
            Set.of(),
            Set.of(nodeType(ELSE_CLAUSE)));

    private static final SourceContent EMPTY_SOURCE = SourceContent.of("");

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root) {
        return CognitiveComplexitySupport.compute(root, EMPTY_SOURCE, CONFIG);
    }
}
