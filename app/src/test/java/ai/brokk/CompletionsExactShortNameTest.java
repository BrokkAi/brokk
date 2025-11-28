package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CompletionsExactShortNameTest {

    @Test
    public void testExactShortNameIncludesParentClass() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk.gui;

                                public class Chrome {
                                    public static class AnalyzerStatusStrip {}
                                }
                                """,
                        "src/main/java/ai/brokk/gui/Chrome.java")
                .build()) {

            IAnalyzer analyzer = new JavaAnalyzer(testProject);

            List<CodeUnit> results = Completions.completeSymbols("Chrome", analyzer);
            var fqns = results.stream().map(CodeUnit::fqName).collect(java.util.stream.Collectors.toSet());

            assertTrue(
                    fqns.contains("ai.brokk.gui.Chrome"),
                    "Autocomplete should include the parent class FQN when query equals the short name");
        }
    }

    @Test
    public void testExactShortNameIncludesParentClass_withMethodOnly() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk.gui;

                                public class Chrome {
                                    public void analyze() {}
                                    private int helper() { return 42; }
                                    public static void main(String[] args) {}
                                }
                                """,
                        "src/main/java/ai/brokk/gui/Chrome.java")
                .build()) {

            IAnalyzer analyzer = new JavaAnalyzer(testProject);

            List<CodeUnit> results = Completions.completeSymbols("Chrome", analyzer);
            var fqns = results.stream().map(CodeUnit::fqName).collect(java.util.stream.Collectors.toSet());

            assertTrue(
                    fqns.contains("ai.brokk.gui.Chrome"),
                    "Autocomplete should include the parent class FQN for short-name query when only methods are present");
        }
    }

    @Test
    public void testExactShortNameIncludesParentClass_withFieldOnly() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk.gui;

                                public class Chrome {
                                    public static final int VERSION = 1;
                                    private String name = "chrome";
                                }
                                """,
                        "src/main/java/ai/brokk/gui/Chrome.java")
                .build()) {

            IAnalyzer analyzer = new JavaAnalyzer(testProject);

            List<CodeUnit> results = Completions.completeSymbols("Chrome", analyzer);
            var fqns = results.stream().map(CodeUnit::fqName).collect(java.util.stream.Collectors.toSet());

            assertTrue(
                    fqns.contains("ai.brokk.gui.Chrome"),
                    "Autocomplete should include the parent class FQN for short-name query when only fields are present");
        }
    }
}
