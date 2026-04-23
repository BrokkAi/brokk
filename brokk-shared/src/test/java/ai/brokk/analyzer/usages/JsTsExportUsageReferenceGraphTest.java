package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    public void localBarrelReexport_isFollowed() throws Exception {
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
                const service = new LayoutService();
                """;

        try (var project = InlineTestProjectCreator.code(service, "layout.service.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(consumer, "consumer.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "layout.service.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile, "LayoutService", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(2, result.hits().size());
            assertTrue(
                    result.hits().stream().anyMatch(hit -> hit.file().toString().endsWith("index.ts")));
            assertTrue(
                    result.hits().stream().anyMatch(hit -> hit.file().toString().endsWith("consumer.ts")));
        }
    }

    @Test
    public void aliasedLocalBarrelReexport_isFollowed() throws Exception {
        String service = """
                export class LayoutService {}
                """;
        String index =
                """
                import { LayoutService } from "./layout.service";
                export { LayoutService as PublicLayoutService };
                """;
        String consumer =
                """
                import { PublicLayoutService } from "./index";
                new PublicLayoutService();
                """;

        try (var project = InlineTestProjectCreator.code(service, "layout.service.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(consumer, "consumer.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "layout.service.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile, "LayoutService", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(2, result.hits().size());
            assertTrue(
                    result.hits().stream().anyMatch(hit -> hit.file().toString().endsWith("index.ts")));
            assertTrue(
                    result.hits().stream().anyMatch(hit -> hit.file().toString().endsWith("consumer.ts")));
        }
    }

    @Test
    public void chainedLocalBarrelReexport_isFollowed() throws Exception {
        String service = """
                export class LayoutService {}
                """;
        String index =
                """
                import { LayoutService } from "./layout.service";
                export { LayoutService };
                """;
        String featureIndex = """
                export { LayoutService } from "../index";
                """;
        String consumer =
                """
                import { LayoutService } from "./feature/index";
                new LayoutService();
                """;

        try (var project = InlineTestProjectCreator.code(service, "layout.service.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(featureIndex, "feature/index.ts")
                .addFileContents(consumer, "consumer.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "layout.service.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile, "LayoutService", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(2, result.hits().size());
            assertTrue(
                    result.hits().stream().anyMatch(hit -> hit.file().toString().endsWith("index.ts")));
            assertTrue(
                    result.hits().stream().anyMatch(hit -> hit.file().toString().endsWith("consumer.ts")));
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
    public void constructorParameterProperty_importedClass_countsAsTypeReferenceUsage() throws Exception {
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
                import { LayoutService } from "../services";

                export class HeaderComponent {
                  constructor(private layoutService: LayoutService) {}
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "services/layout.service.ts")
                .addFileContents(index, "services/index.ts")
                .addFileContents(consumer, "feature/header.component.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "services/layout.service.ts");

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile, "LayoutService", analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());

            assertEquals(2, result.hits().size());
            assertTrue(result.hits().stream()
                    .anyMatch(hit -> hit.file().toString().endsWith("feature/header.component.ts")
                            && hit.kind() == ReferenceKind.TYPE_REFERENCE));
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
    public void aliasedReceiver_onConstructedInstance_resolvesMemberUsage() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const x = new Foo();
                const y = x;
                y.bar();
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
    public void nestedScopeAlias_canUseOuterSeededReceiver() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const x = new Foo();
                {
                  const y = x;
                  y.bar();
                }
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
    public void nestedScopeShadowing_blocksOuterSeededReceiverUsage() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const x = new Foo();
                {
                  const x = { bar() {} };
                  x.bar();
                }
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
    public void unknownAliasSource_doesNotCountAsMemberUsage() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const y = missing;
                y.bar();
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
    public void typedLocalReceiver_resolvesMemberUsage() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                declare const seed: Foo;
                const x: Foo = seed;
                x.bar();
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
    public void typedParameterReceiver_resolvesMemberUsage() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                function run(x: Foo) {
                  x.bar();
                }
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
    public void sameIdentifierInTypeAndValueSpace_doesNotLeakReceiverFactsAcrossScopes() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                function run(value: Foo) {
                  {
                    const value = { bar() {} };
                    value.bar();
                  }
                }
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
    public void ambiguousTypedUnionBeyondCap_doesNotCountAsMemberUsage() throws Exception {
        String a =
                """
                export class A { bar() {} }
                export class B { bar() {} }
                export class C { bar() {} }
                export class D { bar() {} }
                export class E { bar() {} }
                """;
        String b =
                """
                import { A, B, C, D, E } from "./a";
                declare const seed: A | B | C | D | E;
                const value: A | B | C | D | E = seed;
                value.bar();
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
                    aFile, "A", target, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            assertEquals(0, result.hits().size());
        }
    }

    @Test
    public void ambiguousTypedUnionUnderCap_producesBoundedLowConfidenceHits() throws Exception {
        String a =
                """
                export class A { bar() {} }
                export class B { bar() {} }
                """;
        String b =
                """
                import { A, B } from "./a";
                declare const seed: A | B;
                const value: A | B = seed;
                value.bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            CodeUnit targetA = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.shortName().startsWith("A."))
                    .filter(cu -> cu.identifier().startsWith("bar"))
                    .findFirst()
                    .orElseThrow();
            CodeUnit targetB = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.shortName().startsWith("B."))
                    .filter(cu -> cu.identifier().startsWith("bar"))
                    .findFirst()
                    .orElseThrow();

            var resultA = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "A", targetA, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);
            var resultB = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "B", targetB, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            assertEquals(1, resultA.hits().size());
            assertEquals(1, resultB.hits().size());
            assertTrue(resultA.hits().iterator().next().confidence() < 0.95);
            assertTrue(resultB.hits().iterator().next().confidence() < 0.95);
        }
    }

    @Test
    public void aliasChainBeyondOneHop_keepsWorkingWithLowerConfidence() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const x = new Foo();
                const y = x;
                const z = y;
                x.bar();
                z.bar();
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

            var confidences = result.hits().stream()
                    .map(ReferenceHit::confidence)
                    .sorted()
                    .toList();
            assertEquals(2, confidences.size());
            assertTrue(confidences.getLast() > confidences.getFirst());
        }
    }

    @Test
    public void receiverInference_confidenceOrdersDirectAboveConstructedAboveAliased() throws Exception {
        String a =
                """
                export class Foo {
                  static make() {}
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                Foo.make();
                const x = new Foo();
                x.bar();
                const y = x;
                y.bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            CodeUnit staticTarget = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.identifier().startsWith("make"))
                    .findFirst()
                    .orElseThrow();
            CodeUnit instanceTarget = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.identifier().startsWith("bar"))
                    .findFirst()
                    .orElseThrow();

            var staticResult = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", staticTarget, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);
            var instanceResult = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", instanceTarget, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            double directConfidence = staticResult.hits().iterator().next().confidence();
            var instanceConfidences = instanceResult.hits().stream()
                    .map(ReferenceHit::confidence)
                    .sorted()
                    .toList();

            assertEquals(2, instanceConfidences.size());
            assertTrue(directConfidence > instanceConfidences.getLast());
            assertTrue(instanceConfidences.getLast() > instanceConfidences.getFirst());
        }
    }

    @Test
    public void receiverCandidateExtraction_isCachedPerFile() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const x = new Foo();
                x.bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            ImportBinder binder = analyzer.importBinderOf(bFile);

            var first = analyzer.resolvedReceiverCandidatesOf(bFile, binder);
            var second = analyzer.resolvedReceiverCandidatesOf(bFile, binder);

            assertSame(first, second);
        }
    }

    @Test
    public void receiverInference_isSkippedForPlainExportQueries() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                const x = new Foo();
                x.bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            ProjectFile bFile = projectFile(project.getAllFiles(), "b.ts");
            CodeUnit classTarget = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.kind() == ai.brokk.analyzer.CodeUnitType.CLASS)
                    .findFirst()
                    .orElseThrow();

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Foo", classTarget, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            assertEquals(1, result.hits().size());
            assertFalse(analyzer.hasCachedReceiverCandidates(bFile));
        }
    }

    @Test
    public void projectWideUsageIndexes_areCachedAcrossQueries() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String index = """
                export { Foo } from "./a";
                """;
        String b =
                """
                import { Foo } from "./index";
                const x = new Foo();
                x.bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(index, "index.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);

            var reverse1 = analyzer.reverseReexportIndex();
            var reverse2 = analyzer.reverseReexportIndex();
            var heritage1 = analyzer.heritageIndex();
            var heritage2 = analyzer.heritageIndex();

            assertSame(reverse1, reverse2);
            assertSame(heritage1, heritage2);
        }
    }

    @Test
    public void exportResolution_isCachedAcrossQueries() throws Exception {
        String a = """
                export class Foo {}
                """;
        String index = """
                export { Foo } from "./a";
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(index, "index.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile indexFile = projectFile(project.getAllFiles(), "index.ts");
            var expected = new JsTsAnalyzer.ExportResolutionData(Set.of(), Set.of(), Set.of("cached"));

            var first = analyzer.cachedExportResolution(
                    indexFile,
                    "Foo",
                    JsTsExportUsageReferenceGraph.Limits.defaults().maxReexportDepth(),
                    () -> expected);
            var second = analyzer.cachedExportResolution(
                    indexFile,
                    "Foo",
                    JsTsExportUsageReferenceGraph.Limits.defaults().maxReexportDepth(),
                    () -> new JsTsAnalyzer.ExportResolutionData(Set.of(), Set.of(), Set.of("other")));

            assertSame(first, second);
            assertSame(expected, first);
        }
    }

    @Test
    public void memberResolutionIndex_isCachedPerFile() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts").build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");

            var first = analyzer.memberResolutionIndex(aFile);
            var second = analyzer.memberResolutionIndex(aFile);

            assertSame(first, second);
        }
    }

    @Test
    public void simpleFactoryReturn_infersReceiverForMemberUsage() throws Exception {
        String a =
                """
                export class Foo {
                  bar() {}
                }
                """;
        String b =
                """
                import { Foo } from "./a";
                function makeFoo() {
                  return new Foo();
                }
                const value = makeFoo();
                value.bar();
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
    public void typeofTypeQuery_countsAsTypeReferenceUsage() throws Exception {
        String a = """
                export class Foo {}
                """;
        String b =
                """
                import { Foo } from "./a";
                type T = typeof Foo;
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
    public void interfaceMemberInheritance_matchesImplementingClassMemberUsage() throws Exception {
        String a =
                """
                export interface Base {
                  bar(): void;
                }
                export class Child implements Base {
                  bar() {}
                }
                """;
        String b =
                """
                import { Child } from "./a";
                new Child().bar();
                """;

        try (var project = InlineTestProjectCreator.code(a, "a.ts")
                .addFileContents(b, "b.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "a.ts");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(aFile))
                    .filter(cu -> cu.shortName().startsWith("Base."))
                    .filter(cu -> cu.identifier().startsWith("bar"))
                    .findFirst()
                    .orElseThrow();

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    aFile, "Base", target, analyzer, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

            assertEquals(1, result.hits().size());
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
