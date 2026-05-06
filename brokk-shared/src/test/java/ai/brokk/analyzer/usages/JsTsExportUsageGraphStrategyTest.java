package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypescriptAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JsTsExportUsageGraphStrategyTest extends AbstractUsageReferenceGraphTest {

    @Test
    public void exportedFunction_infersExportName_andFindsUsagesWithinCandidates() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b = """
                import { foo } from "./a";
                foo();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            CodeUnit target = analyzer.getDefinitions("foo").stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .findFirst()
                    .orElseThrow();

            var strategy = new JsTsExportUsageGraphStrategy(analyzer);
            var result = strategy.findUsages(List.of(target), Set.of(bFile), 1000);

            assertEquals(
                    1,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    public void exportedFunction_candidatesExcludeUsageFile_returnsEmpty() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b = """
                import { foo } from "./a";
                foo();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            CodeUnit target = analyzer.getDefinitions("foo").stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .findFirst()
                    .orElseThrow();

            var strategy = new JsTsExportUsageGraphStrategy(analyzer);
            var result = strategy.findUsages(List.of(target), Set.of(aFile), 1000);

            assertEquals(
                    0,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    public void exportedClass_barrelReexportFindsUsageThroughIndex() throws Exception {
        String service = """
                export class LayoutService {}
                """;
        String index =
                """
                import { LayoutService } from "./layout.service";
                export { LayoutService };
                """;
        String consumer =
                """
                import { LayoutService } from "./index";
                new LayoutService();
                """;

        try (var project = InlineTestProjectCreator.code(service, "layout.service.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(consumer, "consumer.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "layout.service.ts");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.ts");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> "LayoutService".equals(cu.identifier()))
                    .findFirst()
                    .orElseThrow();

            var strategy = new JsTsExportUsageGraphStrategy(analyzer);
            var result = strategy.findUsages(List.of(target), Set.of(consumerFile), 1000);

            assertEquals(
                    2,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    public void defaultExport_localNameInferred_mapsToDefaultExportName() throws Exception {
        String a = """
                export default function foo() {}
                """;
        String b = """
                import X from "./a";
                X();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            CodeUnit target = analyzer.getDefinitions("foo").stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .findFirst()
                    .orElseThrow();

            var strategy = new JsTsExportUsageGraphStrategy(analyzer);
            var result = strategy.findUsages(List.of(target), Set.of(bFile), 1000);

            assertEquals(
                    1,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    public void exportedClassMember_ownerExportNameInferred_mapsToMemberUsages() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b = """
                import { Foo } from "./a";
                new Foo().bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.identifier().startsWith("bar"))
                    .findFirst()
                    .orElseThrow();

            var strategy = new JsTsExportUsageGraphStrategy(analyzer);
            var result = strategy.findUsages(List.of(target), Set.of(bFile), 1000);

            assertEquals(
                    1,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    public void nonExportedFunction_cannotUseGraphSeed() throws Exception {
        String a = """
                function helper() {}
                helper();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts").build()) {
            var analyzer = new TypescriptAnalyzer(project);
            CodeUnit target =
                    analyzer.getDefinitions("helper").stream().findFirst().orElseThrow();

            var strategy = new JsTsExportUsageGraphStrategy(analyzer);

            assertFalse(strategy.canHandle(target));
        }
    }
}
