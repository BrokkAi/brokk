package ai.brokk.analyzer.cpp;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of("if_statement"),
            Set.of("for_statement", "while_statement", "do_statement", "range_based_for_statement"),
            Set.of("catch_clause"),
            Set.of("conditional_expression"),
            Set.of("case_statement"),
            Set.of("default_statement"),
            Set.of("binary_expression"),
            Set.of("&&", "||", "and", "or"),
            Set.of("break_statement", "continue_statement"),
            Set.of("function_definition"),
            Set.of("lambda_expression"),
            Set.of("else_clause"));

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
