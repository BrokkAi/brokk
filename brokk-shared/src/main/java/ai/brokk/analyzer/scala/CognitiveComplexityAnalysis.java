package ai.brokk.analyzer.scala;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of("if_expression"),
            Set.of("for_expression", "while_expression"),
            Set.of(),
            Set.of(),
            Set.of("case_clause"),
            Set.of(),
            Set.of("infix_expression"),
            Set.of("&&", "||"),
            Set.of("break_expression", "continue_expression"),
            Set.of("function_definition"),
            Set.of("lambda_expression", "anonymous_function"),
            Set.of("else_clause"));

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
