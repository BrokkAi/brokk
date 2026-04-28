package ai.brokk.analyzer.php;

import ai.brokk.analyzer.CognitiveComplexitySupport;
import ai.brokk.analyzer.SourceContent;
import java.util.Set;
import org.treesitter.TSNode;

public final class CognitiveComplexityAnalysis {

    private static final CognitiveComplexitySupport.Config CONFIG = new CognitiveComplexitySupport.Config(
            Set.of("if_statement", "else_if_clause"),
            Set.of("for_statement", "foreach_statement", "while_statement", "do_statement"),
            Set.of("catch_clause"),
            Set.of("conditional_expression"),
            Set.of("case_statement", "match_condition"),
            Set.of("default_statement"),
            Set.of("binary_expression"),
            Set.of("&&", "||", "and", "or", "??"),
            Set.of("break_statement", "continue_statement"),
            Set.of("function_definition", "method_declaration"),
            Set.of("anonymous_function", "arrow_function"),
            Set.of("else_clause"));

    private CognitiveComplexityAnalysis() {}

    public static int compute(TSNode root, SourceContent sourceContent) {
        return CognitiveComplexitySupport.compute(root, sourceContent, CONFIG);
    }
}
