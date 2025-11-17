package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.HashSet;
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
            var resolvedImports = analyzer.importedCodeUnitsOf(fooFile);

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
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

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
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

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
            var resolvedImports = analyzer.importedCodeUnitsOf(fooFile);

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
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var fqNames = resolvedImports.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

            assertEquals(
                    3, resolvedImports.size(), "Should resolve 1 explicit + 2 wildcard imports (excluding static)");
            assertTrue(fqNames.contains("pkg1.TypeA"), "Should include explicit import pkg1.TypeA");
            assertTrue(fqNames.contains("pkg2.TypeB"), "Should include wildcard import pkg2.TypeB");
            assertTrue(fqNames.contains("pkg2.TypeC"), "Should include wildcard import pkg2.TypeC");
        }
    }
}
