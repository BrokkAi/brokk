package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypescriptAnalyzer;
import ai.brokk.analyzer.javascript.TsConfigPathsResolver;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JsTsExportUsageReferenceGraphTest extends AbstractUsageReferenceGraphTest {

    @Test
    public void namedImportAlias_resolvesToExportedSymbol() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b = """
                import { foo as bar } from "./a";
                bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);

            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            var hit = result.hits().iterator().next();
            assertTrue(hit.file().toString().endsWith("b.ts"));
            assertEquals("foo", hit.resolved().identifier());
        }
    }

    @Test
    public void namespaceImport_resolvesMemberReference() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b = """
                import * as NS from "./a";
                NS.foo();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    public void reexportChain_isFollowed() throws Exception {
        String a = """
                export const foo = 1;
                """;
        String index = """
                export { foo } from "./a";
                """;
        String b = """
                import { foo } from "./index";
                foo;
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile indexFile = projectFile(project.getAllFiles(), "index.ts");
            assertTrue(analyzer.exportIndexOf(indexFile).exportsByName().containsKey("foo"));

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            assertTrue(result.hits().iterator().next().file().toString().endsWith("b.ts"));
        }
    }

    @Test
    public void localShadowing_doesNotCountAsUsage() throws Exception {
        String a = """
                export function foo() {}
                """;
        String b =
                """
                import { foo as bar } from "./a";
                function f() {
                  const bar = 1;
                  bar;
                }
                bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            // Only the call to imported bar() should count.
            assertEquals(1, result.hits().size());
        }
    }

    @Test
    public void classInheritance_polymorphicClassUsageCounts() throws Exception {
        String a =
                """
                export class Base {}
                export class Child extends Base {}
                """;
        String b = """
                import { Child } from "./a";
                new Child();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Base", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    public void typeAnnotation_importedClass_countsAsTypeReferenceUsage() throws Exception {
        String a = """
                export class Foo {}
                """;
        String b =
                """
                import { Foo } from "./a";
                const value: Foo | null = null;
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            assertEquals(
                    ReferenceKind.TYPE_REFERENCE,
                    result.hits().iterator().next().kind());
        }
    }

    @Test
    public void genericTypeArgument_importedClass_countsAsTypeReferenceUsage() throws Exception {
        String a =
                """
                export class Foo {}
                export type Box<T> = { value: T };
                """;
        String b =
                """
                import { Foo, Box } from "./a";
                const value: Box<Foo> = { value: null as Foo };
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(2, result.hits().size());
        }
    }

    @Test
    public void returnType_importedClass_countsAsTypeReferenceUsage() throws Exception {
        String a = """
                export class Foo {}
                """;
        String b =
                """
                import { Foo } from "./a";
                function load(): Foo {
                  return null as Foo;
                }
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(2, result.hits().size());
        }
    }

    @Test
    public void instanceMember_onImportedClass_resolvesMemberUsage() throws Exception {
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
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.identifier().startsWith("bar"))
                    .findFirst()
                    .orElseThrow();

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", target, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    public void staticMember_onNamespaceImportedClass_resolvesMemberUsage() throws Exception {
        String a =
                """
                export class Foo {
                  static make() {}
                }
                """;
        String b = """
                import * as NS from "./a";
                NS.Foo.make();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.identifier().startsWith("make"))
                    .findFirst()
                    .orElseThrow();

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", target, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    public void unresolvedReceiverPropertyAccess_doesNotCountAsMemberUsage() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const obj = { bar() {} };
                obj.bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.identifier().startsWith("bar"))
                    .findFirst()
                    .orElseThrow();

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", target, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            assertEquals(0, result.hits().size());
        }
    }

    @Test
    public void defaultExport_importedAndUsed_countsAsUsage() throws Exception {
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "default", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            assertTrue(result.hits().iterator().next().file().toString().endsWith("b.ts"));
        }
    }

    @Test
    public void defaultExport_reexportedAndConsumed_isFollowed() throws Exception {
        String a = """
                export default function foo() {}
                """;
        String index = """
                export { default as Y } from "./a";
                """;
        String b = """
                import { Y } from "./index";
                Y();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "default", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            assertTrue(result.hits().iterator().next().file().toString().endsWith("b.ts"));
        }
    }

    @Test
    public void tsconfigPathsAlias_importResolves_countsUsage() throws Exception {
        String tsconfig =
                """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": {
                      "@/*": ["src/*"]
                    }
                  }
                }
                """;
        String a = """
                export function foo() {}
                """;
        String b = """
                import { foo } from "@/a";
                foo();
                """;

        try (var project = InlineTestProjectCreator.code(tsconfig, "tsconfig.json")
                .addFileContents(a, "src/a.ts")
                .addFileContents(b, "src/b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "src/a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "src/b.ts");

            TsConfigPathsResolver.Expansion expansion =
                    new TsConfigPathsResolver(project.getRoot()).expand(bFile, "@/a");
            assertTrue(expansion.hadAnyMapping());
            assertTrue(expansion.candidates().stream()
                    .map(s -> s.replace('\\', '/'))
                    .anyMatch(s -> s.equals("src/a")));

            assertTrue(analyzer.importStatementsOf(bFile).stream()
                    .anyMatch(s -> s.contains("\"@/a\"") || s.contains("'@/a'")));
            assertEquals(
                    aFile,
                    analyzer.resolveEsmModuleOutcome(bFile, "@/a").resolved().orElseThrow());
            assertEquals(
                    Set.of("@/a"),
                    analyzer.importInfoOf(bFile).stream()
                            .map(ii -> JsTsAnalyzer.extractModulePathFromImport(ii.rawSnippet())
                                    .orElse("MISSING"))
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(analyzer.couldImportFile(bFile, analyzer.importInfoOf(bFile), aFile));
            assertTrue(analyzer.referencingFilesOf(aFile).contains(bFile));

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            assertTrue(result.hits().iterator().next().file().toString().endsWith("src/b.ts"));
        }
    }

    @Test
    public void tsconfigPathsAlias_reexportChainResolves_countsUsage() throws Exception {
        String tsconfig =
                """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": {
                      "@/*": ["src/*"]
                    }
                  }
                }
                """;
        String a = """
                export function foo() {}
                """;
        String index = """
                export { foo } from "@/a";
                """;
        String b = """
                import { foo } from "@/index";
                foo();
                """;

        try (var project = InlineTestProjectCreator.code(tsconfig, "tsconfig.json")
                .addFileContents(a, "src/a.ts")
                .addFileContents(index, "src/index.ts")
                .addFileContents(b, "src/b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "src/a.ts");
            ProjectFile indexFile = projectFile(project.getAllFiles(), "src/index.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "src/b.ts");

            assertTrue(analyzer.resolveEsmModuleOutcome(bFile, "@/index")
                    .resolved()
                    .isPresent());
            assertTrue(analyzer.exportIndexOf(indexFile).exportsByName().containsKey("foo"));
            assertTrue(analyzer.resolveEsmModuleOutcome(indexFile, "@/a")
                    .resolved()
                    .isPresent());

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(1, result.hits().size());
            assertTrue(result.hits().iterator().next().file().toString().endsWith("src/b.ts"));
        }
    }

    @Test
    public void grepFalsePositive_commentAndString_noImportBinding_noUsage() throws Exception {
        String a =
                """
                export function foo() {}
                export const bar = 1;
                """;
        String b =
                """
                import "./a";
                // foo()
                const s = "foo";
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(0, result.hits().size());
        }
    }

    @Test
    public void grepFalsePositive_propertyAndKey_noImportBinding_noUsage() throws Exception {
        String a =
                """
                export function foo() {}
                export const bar = 1;
                """;
        String b =
                """
                import { bar } from "./a";
                const obj = { foo: 1 };
                obj.foo;
                bar;
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(0, result.hits().size());
        }
    }

    @Test
    public void externalModuleReexport_isFrontier_notAnalyzed() throws Exception {
        String tsconfig =
                """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": {
                      "~/*": ["../external/*"]
                    }
                  }
                }
                """;
        String index = """
                export { foo } from "~/lib";
                """;

        try (var project = InlineTestProjectCreator.code(tsconfig, "tsconfig.json")
                .addFileContents(index, "src/index.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile indexFile = projectFile(project.getAllFiles(), "src/index.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    indexFile, "foo", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(0, result.hits().size());
            assertTrue(result.externalFrontierSpecifiers().contains("~/lib"));
        }
    }
}
