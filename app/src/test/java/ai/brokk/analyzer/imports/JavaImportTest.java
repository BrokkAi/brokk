package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class JavaImportTest {

    @Test
    public void testOrdinaryImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import foo.bar.Baz;
                import Bar;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.Baz;", "import Bar;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testStaticImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import static foo.bar.Baz.method;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import static foo.bar.Baz.method;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testWildcardImport() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import foo.bar.*;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var imports = analyzer.importStatementsOf(file);
            var expected = Set.of("import foo.bar.*;");
            assertEquals(expected, new HashSet<>(imports), "Imports should be identical");
        }
    }

    @Test
    public void testResolvedExplicitImport() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package example;
                public class Baz {}
                """,
                "Baz.java");
        try (var testProject = builder.addFileContents(
                        """
                import example.Baz;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var fooFile = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var resolvedImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fooFile))
                    .orElse(Set.of());

            var bazCUs = resolvedImports.stream()
                    .filter(cu -> cu.fqName().equals("example.Baz"))
                    .collect(Collectors.toList());

            assertEquals(1, bazCUs.size(), "Should resolve import example.Baz to one CodeUnit");
            assertTrue(bazCUs.getFirst().isClass(), "Resolved import should be a class");
        }
    }

    @Test
    public void testResolvedWildcardImport() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package sample;
                public class ClassA {}
                """,
                "ClassA.java");
        try (var testProject = builder.addFileContents(
                        """
                package sample;
                public class ClassB {}
                """,
                        "ClassB.java")
                .addFileContents(
                        """
                import sample.*;

                public class Consumer {}
                """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(consumerFile))
                    .orElse(Set.of());

            var sampleClasses = resolvedImports.stream()
                    .filter(cu -> cu.fqName().startsWith("sample."))
                    .map(cu -> cu.fqName())
                    .collect(Collectors.toSet());

            assertEquals(2, sampleClasses.size(), "Wildcard import should resolve to 2 classes in sample package");
            assertTrue(sampleClasses.contains("sample.ClassA"), "Should resolve sample.ClassA");
            assertTrue(sampleClasses.contains("sample.ClassB"), "Should resolve sample.ClassB");
        }
    }

    @Test
    public void testResolvedImportsDoesNotIncludeStaticImports() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package util;
                public class Helper {
                    public static void doSomething() {}
                }
                """,
                "Helper.java");
        try (var testProject = builder.addFileContents(
                        """
                import static util.Helper.doSomething;
                import util.Helper;

                public class Consumer {}
                """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(consumerFile))
                    .orElse(Set.of());

            var helperCUs = resolvedImports.stream()
                    .filter(cu -> cu.fqName().equals("util.Helper"))
                    .collect(Collectors.toList());

            assertEquals(1, helperCUs.size(), "Should resolve explicit import but not static import");
        }
    }

    @Test
    public void testResolvedImportsEmptyForUnresolvedImports() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import nonexistent.package.Class;

                public class Foo {}
                """,
                        "Foo.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var fooFile = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var resolvedImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fooFile))
                    .orElse(Set.of());

            assertTrue(resolvedImports.isEmpty(), "Unresolved imports should result in empty resolved set");
        }
    }

    @Test
    public void testMixedImportResolution() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package pkg1;
                public class TypeA {}
                """,
                "TypeA.java");
        try (var testProject = builder.addFileContents(
                        """
                package pkg2;
                public class TypeB {}
                public class TypeC {}
                """,
                        "TypeB.java")
                .addFileContents(
                        """
                import pkg1.TypeA;
                import pkg2.*;
                import static java.lang.System.out;

                public class Consumer {}
                """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(consumerFile))
                    .orElse(Set.of());

            var fqNames = resolvedImports.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

            assertEquals(
                    3, resolvedImports.size(), "Should resolve 1 explicit + 2 wildcard imports (excluding static)");
            assertTrue(fqNames.contains("pkg1.TypeA"), "Should include explicit import pkg1.TypeA");
            assertTrue(fqNames.contains("pkg2.TypeB"), "Should include wildcard import pkg2.TypeB");
            assertTrue(fqNames.contains("pkg2.TypeC"), "Should include wildcard import pkg2.TypeC");
        }
    }

    @Test
    public void testExplicitImportHasPrecedenceOverWildcard() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package pkg1;
                public class Ambiguous {}
                """,
                "Ambiguous1.java");
        try (var testProject = builder.addFileContents(
                        """
                        package pkg2;
                        public class Ambiguous {}
                        """,
                        "Ambiguous2.java")
                .addFileContents(
                        """
                        package consumer;
                        import pkg1.Ambiguous; // explicit import
                        import pkg2.*;        // wildcard import

                        public class Consumer {
                            private Ambiguous field;
                        }
                        """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile = analyzer.getDefinitions("consumer.Consumer").stream()
                    .findFirst()
                    .get()
                    .source();
            var resolvedImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(consumerFile))
                    .orElse(Set.of());

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class");
            assertEquals(
                    "pkg1.Ambiguous", ambiguousCUs.getFirst().fqName(), "Explicitly imported class should be chosen");
        }
    }

    @Test
    public void testAmbiguousWildcardImportsAreResolvedDeterministically() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                package pkg1;
                public class Ambiguous {}
                """,
                "Ambiguous1.java");
        try (var testProject = builder.addFileContents(
                        """
                        package pkg2;
                        public class Ambiguous {}
                        """,
                        "Ambiguous2.java")
                .addFileContents(
                        """
                        package consumer;
                        import pkg1.*; // first wildcard
                        import pkg2.*; // second wildcard

                        public class Consumer {
                            private Ambiguous field;
                        }
                        """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile = analyzer.getDefinitions("consumer.Consumer").stream()
                    .findFirst()
                    .get()
                    .source();
            var resolvedImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(consumerFile))
                    .orElse(Set.of());

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class from wildcards");
            assertEquals(
                    "pkg1.Ambiguous",
                    ambiguousCUs.getFirst().fqName(),
                    "First wildcard import should win for ambiguous simple names");
        }
    }

    @Test
    public void testCircularImportsABC() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                package pkg;
                import pkg.B;
                public class A {}
                """,
                        "A.java")
                .addFileContents(
                        """
                package pkg;
                import pkg.C;
                public class B {}
                """,
                        "B.java")
                .addFileContents(
                        """
                package pkg;
                import pkg.A;
                public class C {}
                """,
                        "C.java");

        try (var testProject = builder.build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var fileA = AnalyzerUtil.getFileFor(analyzer, "pkg.A").get();
            var fileB = AnalyzerUtil.getFileFor(analyzer, "pkg.B").get();
            var fileC = AnalyzerUtil.getFileFor(analyzer, "pkg.C").get();

            // Verify forward imports
            var importsA = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fileA))
                    .orElse(Set.of());
            assertTrue(importsA.stream().anyMatch(cu -> cu.fqName().equals("pkg.B")), "A should import B");

            var importsB = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fileB))
                    .orElse(Set.of());
            assertTrue(importsB.stream().anyMatch(cu -> cu.fqName().equals("pkg.C")), "B should import C");

            var importsC = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fileC))
                    .orElse(Set.of());
            assertTrue(importsC.stream().anyMatch(cu -> cu.fqName().equals("pkg.A")), "C should import A");

            // Verify reverse edges
            var refsA = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.referencingFilesOf(fileA))
                    .orElse(Set.of());
            assertTrue(refsA.contains(fileC), "C.java should be a referencing file of A.java");

            var refsB = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.referencingFilesOf(fileB))
                    .orElse(Set.of());
            assertTrue(refsB.contains(fileA), "A.java should be a referencing file of B.java");

            var refsC = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.referencingFilesOf(fileC))
                    .orElse(Set.of());
            assertTrue(refsC.contains(fileB), "B.java should be a referencing file of C.java");
        }
    }

    @Test
    public void testCircularImportsRecursionGuard() throws IOException {
        // This test ensures that the recursion guard in TreeSitterAnalyzer prevents StackOverflowError
        var builder = InlineTestProjectCreator.code(
                        """
                import B;
                public class A {}
                """, "A.java")
                .addFileContents(
                        """
                import A;
                public class B {}
                """, "B.java");

        try (var testProject = builder.build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var fileA = AnalyzerUtil.getFileFor(analyzer, "A").get();

            // This call triggers resolveImports -> getDefinitions -> importedCodeUnitsOf...
            // If the guard fails, this will throw StackOverflowError
            var resolved = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fileA))
                    .orElse(Set.of());
            assertEquals(1, resolved.size());
            assertEquals("B", resolved.iterator().next().fqName());
        }
    }

    @Test
    public void testCircularImportsConsistency() throws IOException {
        var builder = InlineTestProjectCreator.code(
                        """
                package pkg;
                import pkg.B;
                public class A {}
                """,
                        "A.java")
                .addFileContents(
                        """
                package pkg;
                import pkg.A;
                public class B {}
                """,
                        "B.java");

        try (var testProject = builder.build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var fileA = AnalyzerUtil.getFileFor(analyzer, "pkg.A").get();

            var firstCall = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fileA))
                    .orElse(Set.of());
            var secondCall = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importedCodeUnitsOf(fileA))
                    .orElse(Set.of());

            assertEquals(firstCall, secondCall, "Subsequent calls should return identical cached results");
            assertEquals(1, firstCall.size());
            assertEquals("pkg.B", firstCall.iterator().next().fqName());
        }
    }

    @Test
    public void testImportInfoStructure() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                import java.util.List;
                import java.util.Map;
                import static java.lang.Math.PI;
                import com.example.*;
                import static org.junit.Assert.*;

                public class Foo {}
                """,
                "Foo.java");
        try (var testProject = builder.build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var fooFile = AnalyzerUtil.getFileFor(analyzer, "Foo").get();
            var importInfos = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.importInfoOf(fooFile))
                    .orElse(List.of());

            assertEquals(5, importInfos.size(), "Should have 5 import statements");

            // Find specific imports and verify their structure
            var listImport = importInfos.stream()
                    .filter(i -> i.rawSnippet().contains("java.util.List"))
                    .findFirst()
                    .orElseThrow();
            assertFalse(listImport.isWildcard(), "List import should not be wildcard");
            assertEquals("List", listImport.identifier(), "Should extract 'List' as identifier");
            assertNull(listImport.alias(), "Java imports don't have aliases");

            var mapImport = importInfos.stream()
                    .filter(i -> i.rawSnippet().contains("java.util.Map"))
                    .findFirst()
                    .orElseThrow();
            assertFalse(mapImport.isWildcard(), "Map import should not be wildcard");
            assertEquals("Map", mapImport.identifier(), "Should extract 'Map' as identifier");

            var staticImport = importInfos.stream()
                    .filter(i -> i.rawSnippet().contains("Math.PI"))
                    .findFirst()
                    .orElseThrow();
            assertFalse(staticImport.isWildcard(), "Static import should not be wildcard");
            assertEquals("PI", staticImport.identifier(), "Should extract 'PI' as identifier from static import");

            var wildcardImport = importInfos.stream()
                    .filter(i -> i.rawSnippet().contains("com.example.*"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(wildcardImport.isWildcard(), "com.example.* should be wildcard");
            assertNull(wildcardImport.identifier(), "Wildcard imports should have null identifier");

            var staticWildcardImport = importInfos.stream()
                    .filter(i -> i.rawSnippet().contains("org.junit.Assert.*"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(staticWildcardImport.isWildcard(), "Static wildcard should be wildcard");
            assertNull(staticWildcardImport.identifier(), "Static wildcard imports should have null identifier");
        }
    }

    @Test
    public void testRelevantImportsForMethod() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
            package pkg;
            public class Foo {}
            """, "Foo.java");
        try (var testProject = builder.addFileContents(
                        """
                    package consumer;
                    import pkg.Foo;

                    public class Consumer {
                        public void bar(Foo a) {
                            // uses Foo
                        }
                    }
                    """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer.Consumer").get();
            var declarations = analyzer.getDeclarations(consumerFile);

            var consumerClass = declarations.stream()
                    .filter(cu -> cu.isClass() && cu.shortName().equals("Consumer"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Consumer class not found in declarations: " + declarations));
            var barMethod = analyzer.getDirectChildren(consumerClass).stream()
                    .filter(cu -> cu.identifier().equals("bar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "bar method not found in children: " + analyzer.getDirectChildren(consumerClass)));

            var relevantImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.relevantImportsFor(barMethod))
                    .orElse(Set.of());

            assertEquals(1, relevantImports.size(), "Should have exactly one relevant import");
            assertTrue(relevantImports.contains("import pkg.Foo;"), "Should include import for Foo");
        }
    }

    @Test
    public void testRelevantImportsExcludesUnused() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
            package pkg;
            public class Foo {}
            """, "Foo.java");
        try (var testProject = builder.addFileContents(
                        """
                    package pkg;
                    public class Bar {}
                    """,
                        "Bar.java")
                .addFileContents(
                        """
                    package consumer;
                    import pkg.Foo;
                    import pkg.Bar;

                    public class Consumer {
                        public void methodUsingOnlyFoo(Foo a) {
                            // only uses Foo, not Bar
                        }
                    }
                    """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer.Consumer").get();
            var declarations = analyzer.getDeclarations(consumerFile);

            var consumerClass = declarations.stream()
                    .filter(cu -> cu.isClass() && cu.shortName().equals("Consumer"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Consumer class not found in declarations: " + declarations));
            var method = analyzer.getDirectChildren(consumerClass).stream()
                    .filter(cu -> cu.identifier().equals("methodUsingOnlyFoo"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "methodUsingOnlyFoo not found in children: " + analyzer.getDirectChildren(consumerClass)));

            var relevantImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.relevantImportsFor(method))
                    .orElse(Set.of());

            assertEquals(1, relevantImports.size(), "Should have exactly one relevant import");
            assertTrue(relevantImports.contains("import pkg.Foo;"), "Should include import for Foo");
            assertFalse(relevantImports.contains("import pkg.Bar;"), "Should NOT include import for Bar");
        }
    }

    @Test
    public void testRelevantImportsIncludesWildcardWhenNeeded() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
            package pkg;
            public class Foo {}
            """, "Foo.java");
        try (var testProject = builder.addFileContents(
                        """
                    package consumer;
                    import pkg.Foo;
                    import other.*;

                    public class Consumer {
                        public void bar(Foo a, UnknownType b) {
                            // Foo is explicitly imported, UnknownType might come from wildcard
                        }
                    }
                    """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer.Consumer").get();
            var declarations = analyzer.getDeclarations(consumerFile);

            var consumerClass = declarations.stream()
                    .filter(cu -> cu.isClass() && cu.shortName().equals("Consumer"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Consumer class not found in declarations: " + declarations));
            var barMethod = analyzer.getDirectChildren(consumerClass).stream()
                    .filter(cu -> cu.identifier().equals("bar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "bar method not found in children: " + analyzer.getDirectChildren(consumerClass)));

            var relevantImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.relevantImportsFor(barMethod))
                    .orElse(Set.of());

            assertEquals(2, relevantImports.size(), "Should have two relevant imports");
            assertTrue(relevantImports.contains("import pkg.Foo;"), "Should include explicit import for Foo");
            assertTrue(
                    relevantImports.contains("import other.*;"),
                    "Should include wildcard import for unresolved UnknownType");
        }
    }

    /**
     * Tests that relevantImportsFor correctly identifies which wildcard import is relevant
     * when a type reference could potentially belong to either an internal project package
     * or an external library.
     *
     * Expected behavior (once implemented):
     * - InternalService is defined in internal.InternalService (known project type)
     * - The analyzer should resolve InternalService via import internal.*
     * - Since InternalService IS resolved to a known type, it should NOT be considered "unresolved"
     * - Only import internal.* should be included (it provides InternalService)
     * - import external.* should be EXCLUDED (it provides nothing used by the method)
     *
     * Current behavior: Both wildcards are included because the analyzer doesn't
     * check whether wildcard imports resolve to known project types. The type identifier
     * "InternalService" is treated as unresolved (no explicit import matches it),
     * causing ALL wildcards to be included.
     */
    @Test
    public void testRelevantImportsResolvesWildcardToKnownProjectType() throws IOException {
        // Create the internal package with InternalService class
        var builder = InlineTestProjectCreator.code(
                """
                package internal;
                public class InternalService {}
                """,
                "internal/InternalService.java");

        try (var testProject = builder.addFileContents(
                        """
                    package consumer;
                    import internal.*;
                    import external.*;

                    public class Consumer {
                        public void process(InternalService svc) {
                            // Uses InternalService which is defined in internal package
                        }
                    }
                    """,
                        "consumer/Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer.Consumer").get();
            var declarations = analyzer.getDeclarations(consumerFile);

            var consumerClass = declarations.stream()
                    .filter(cu -> cu.isClass() && cu.shortName().equals("Consumer"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Consumer class not found"));
            var processMethod = analyzer.getDirectChildren(consumerClass).stream()
                    .filter(cu -> cu.identifier().equals("process"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("process method not found"));

            var relevantImports = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.relevantImportsFor(processMethod))
                    .orElse(Set.of());

            // EXPECTED: Only the internal wildcard should be included because InternalService
            // can be resolved to internal.InternalService (a known project type).
            // The external.* wildcard should be excluded since it doesn't provide any types
            // referenced by this method.
            assertEquals(
                    1,
                    relevantImports.size(),
                    "Only the wildcard that resolves to a known project type should be included");
            assertTrue(
                    relevantImports.contains("import internal.*;"),
                    "Should include internal.* because it provides InternalService");
            assertFalse(
                    relevantImports.contains("import external.*;"),
                    "Should NOT include external.* because it provides no types used by this method");
        }
    }

    @Test
    public void testRelevantImportsExcludesFullyQualifiedTypes() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                package consumer;
                import java.util.List;
                import other.*;

                public class Consumer {
                    public void method(java.util.ArrayList fq, List explicit, UnknownType wildcard) {
                        // java.util.ArrayList is FQ - should not trigger wildcard
                        // List is explicit - should include java.util.List
                        // UnknownType - should include other.*
                    }
                }
                """,
                        "Consumer.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer.Consumer").get();
            var declarations = analyzer.getDeclarations(consumerFile);

            var consumerClass = declarations.stream()
                    .filter(cu -> cu.isClass() && cu.shortName().equals("Consumer"))
                    .findFirst()
                    .get();
            var method = analyzer.getDirectChildren(consumerClass).stream()
                    .filter(cu -> cu.identifier().equals("method"))
                    .findFirst()
                    .get();

            var relevant = analyzer.as(ImportAnalysisProvider.class)
                    .map(p -> p.relevantImportsFor(method))
                    .orElse(Set.of());

            assertTrue(relevant.contains("import java.util.List;"), "Should include explicit import for List");
            assertTrue(relevant.contains("import other.*;"), "Should include wildcard for UnknownType");
            assertEquals(2, relevant.size(), "Should only have 2 imports (ArrayList should not trigger extra wildcards)");
        }
    }

    @Test
    public void testExtractTypeIdentifiersCapturesQualifiedTypes() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                public class Foo {
                    List simple;
                    java.util.List qualified;
                }
                """,
                        "Foo.java")
                .build()) {
            var analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            var pf = testProject.getAnalyzableFiles(Languages.JAVA).stream()
                    .filter(f -> f.getFileName().equals("Foo.java"))
                    .findFirst()
                    .orElseThrow();
            String source = SourceContent.read(pf).get().text();

            Set<String> identifiers = analyzer.extractTypeIdentifiers(source);

            assertTrue(identifiers.contains("List"), "Should capture simple type_identifier 'List'");
            assertTrue(
                    identifiers.contains("java.util.List"),
                    "Should capture scoped_type_identifier 'java.util.List'");
        }
    }
}
