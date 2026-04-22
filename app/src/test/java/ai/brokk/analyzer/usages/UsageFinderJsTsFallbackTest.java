package ai.brokk.analyzer.usages;

import static ai.brokk.testutil.UsageFinderTestUtil.fileNamesFromHits;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.TypescriptAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.usages.UsageFinder;
import org.junit.jupiter.api.Test;

public class UsageFinderJsTsFallbackTest {

    @Test
    public void nonExportedTypescriptSymbol_fallsBackToFuzzyUsageAnalysis() throws Exception {
        String a =
                """
                class Foo {
                  helper() {}
                  run() {
                    this.helper();
                  }
                }
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts").build()) {
            var analyzer = new TypescriptAnalyzer(project);
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.identifier().startsWith("helper"))
                    .findFirst()
                    .orElseThrow();
            var finder = new UsageFinder(
                    project, analyzer, UsageFinder.createDefaultProvider(), new RegexUsageAnalyzer(analyzer), null);

            var either = finder.findUsages(target.fqName()).toEither();

            assertFalse(either.hasErrorMessage());
            assertTrue(fileNamesFromHits(either.getUsages()).contains("a.ts"));
        }
    }

    @Test
    public void exportedTypescriptSymbolWithOnlySameFileUsages_fallsBackToFuzzyUsageAnalysis() throws Exception {
        String a =
                """
                export class BaseClass {
                  static make(): BaseClass {
                    return new BaseClass();
                  }
                }

                class Child extends BaseClass {
                  field: BaseClass = new BaseClass();
                }

                const value: BaseClass = BaseClass.make();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts").build()) {
            var analyzer = new TypescriptAnalyzer(project);
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> "BaseClass".equals(cu.identifier()))
                    .findFirst()
                    .orElseThrow();
            var finder = new UsageFinder(
                    project, analyzer, UsageFinder.createDefaultProvider(), new RegexUsageAnalyzer(analyzer), null);

            var either = finder.findUsages(target.fqName()).toEither();

            assertFalse(either.hasErrorMessage());
            assertTrue(fileNamesFromHits(either.getUsages()).contains("a.ts"));
        }
    }
}
