package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.usages.UsageFinder;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class UsageFinderRustGraphTest {

    @Test
    void appUsageFinderRoutesPublicRustStructToGraph() throws Exception {
        String summary =
                """
                pub struct RenderedSummary {
                    pub label: String,
                    pub text: String,
                }

                pub fn summarize_inputs(inputs: &[String]) -> Result<Vec<RenderedSummary>, String> {
                    inputs
                        .iter()
                        .map(|input| summarize_input(input))
                        .collect()
                }

                fn summarize_input(input: &str) -> Result<RenderedSummary, String> {
                    Ok(RenderedSummary {
                        label: input.to_string(),
                        text: input.to_string(),
                    })
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(summary, "src/summary.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            var target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> "RenderedSummary".equals(cu.identifier()))
                    .findFirst()
                    .orElseThrow();
            var emptyFallback = new UsageFinder(
                    project,
                    analyzer,
                    UsageFinder.createDefaultProvider(),
                    (overloads, candidates, maxUsages) ->
                            new FuzzyResult.Success(Map.of(overloads.getFirst(), Set.of())),
                    null);

            var result = emptyFallback.findUsages(target.fqName()).toEither();

            assertEquals(3, result.getUsages().size());

            var shortNameResult = emptyFallback.findUsages("RenderedSummary").toEither();

            assertFalse(shortNameResult.hasErrorMessage());
            assertEquals(3, shortNameResult.getUsages().size());
            assertEquals(
                    3,
                    UsageRenderer.render(
                                    analyzer,
                                    "RenderedSummary",
                                    java.util.List.of(target),
                                    emptyFallback.findUsages("RenderedSummary"),
                                    UsageRenderer.Mode.SAMPLE)
                            .hitCount());
        }
    }

    @Test
    void appUsageFinderRoutesPrivateSameFileRustFunctionToGraph() throws Exception {
        String summary =
                """
                pub struct RenderedSummary;

                pub fn summarize_inputs(inputs: &[String]) -> Result<Vec<RenderedSummary>, String> {
                    inputs
                        .iter()
                        .map(|input| summarize_input(input))
                        .collect()
                }

                fn summarize_input(input: &str) -> Result<RenderedSummary, String> {
                    Ok(RenderedSummary)
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(summary, "src/summary.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            var target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> "summarize_input".equals(cu.identifier()))
                    .findFirst()
                    .orElseThrow();
            var emptyFallback = new UsageFinder(
                    project,
                    analyzer,
                    UsageFinder.createDefaultProvider(),
                    (overloads, candidates, maxUsages) ->
                            new FuzzyResult.Success(Map.of(overloads.getFirst(), Set.of())),
                    null);

            var result = emptyFallback.findUsages(target.fqName()).toEither();

            assertEquals(1, result.getUsages().size());

            var shortNameResult = emptyFallback.findUsages("summarize_input").toEither();

            assertFalse(shortNameResult.hasErrorMessage());
            assertEquals(1, shortNameResult.getUsages().size());
            assertEquals(
                    1,
                    UsageRenderer.render(
                                    analyzer,
                                    "summarize_input",
                                    java.util.List.of(target),
                                    emptyFallback.findUsages("summarize_input"),
                                    UsageRenderer.Mode.SAMPLE)
                            .hitCount());
        }
    }
}
