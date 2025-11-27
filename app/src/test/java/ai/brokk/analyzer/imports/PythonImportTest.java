package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tests for Python import resolution matching Python's native "last import wins" semantics.
 * In Python, imports are executed in order and later imports override earlier ones with the same name.
 */
public class PythonImportTest {

    @Test
    public void testLastImportWins_WildcardAfterExplicit() throws IOException {
        // In Python: wildcard import after explicit import shadows the explicit one
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg1/__init__.py")
                .addFileContents("# Package marker\n", "pkg2/__init__.py")
                .addFileContents(
                        """
                        class Ambiguous:
                            pass
                        """,
                        "pkg1/ambiguous.py")
                .addFileContents(
                        """
                        class Ambiguous:
                            pass

                        class OtherClass:
                            pass
                        """,
                        "pkg2/ambiguous.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg1.ambiguous import Ambiguous  # explicit import (first)
                        from pkg2.ambiguous import *          # wildcard import (second - wins)

                        class Consumer:
                            def __init__(self):
                                self.field = Ambiguous()
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class");
            assertEquals(
                    "pkg2.Ambiguous",
                    ambiguousCUs.getFirst().fqName(),
                    "Last import wins: wildcard after explicit should shadow the explicit import");
        }
    }

    @Test
    public void testLastImportWins_ExplicitAfterWildcard() throws IOException {
        // In Python: explicit import after wildcard shadows the wildcard one
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg1/__init__.py")
                .addFileContents("# Package marker\n", "pkg2/__init__.py")
                .addFileContents(
                        """
                        class Ambiguous:
                            pass
                        """,
                        "pkg1/ambiguous.py")
                .addFileContents(
                        """
                        class Ambiguous:
                            pass
                        """,
                        "pkg2/ambiguous.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg1.ambiguous import *          # wildcard import (first)
                        from pkg2.ambiguous import Ambiguous  # explicit import (second - wins)

                        class Consumer:
                            pass
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class");
            assertEquals(
                    "pkg2.Ambiguous",
                    ambiguousCUs.getFirst().fqName(),
                    "Last import wins: explicit after wildcard should shadow the wildcard import");
        }
    }

    @Test
    public void testLastWildcardWins() throws IOException {
        // In Python: second wildcard shadows the first when both provide the same name
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg1/__init__.py")
                .addFileContents("# Package marker\n", "pkg2/__init__.py")
                .addFileContents(
                        """
                        class Ambiguous:
                            pass
                        """,
                        "pkg1/ambiguous.py")
                .addFileContents(
                        """
                        class Ambiguous:
                            pass
                        """,
                        "pkg2/ambiguous.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg1.ambiguous import *  # first wildcard
                        from pkg2.ambiguous import *  # second wildcard (wins)

                        class Consumer:
                            pass
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class from wildcards");
            assertEquals(
                    "pkg2.Ambiguous",
                    ambiguousCUs.getFirst().fqName(),
                    "Last import wins: second wildcard should shadow the first");
        }
    }

    @Test
    public void testWildcardImportsPublicClassesOnly() throws IOException {
        // Wildcard imports should only include public classes (no underscore prefix)
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg/__init__.py")
                .addFileContents(
                        """
                        class PublicClass:
                            pass

                        class _PrivateClass:
                            pass

                        class __DunderClass:
                            pass
                        """,
                        "pkg/module.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg.module import *

                        class Consumer:
                            pass
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var importedNames =
                    resolvedImports.stream().map(cu -> cu.identifier()).collect(Collectors.toSet());

            assertTrue(importedNames.contains("PublicClass"), "Should import PublicClass");
            assertFalse(importedNames.contains("_PrivateClass"), "Should NOT import _PrivateClass (underscore prefix)");
            assertFalse(importedNames.contains("__DunderClass"), "Should NOT import __DunderClass (underscore prefix)");
        }
    }

    @Test
    public void testWildcardWithRelativeImport() throws IOException {
        // Relative wildcard imports should work correctly
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg/__init__.py")
                .addFileContents("# Package marker\n", "pkg/sub/__init__.py")
                .addFileContents(
                        """
                        class BaseClass:
                            pass
                        """,
                        "pkg/base.py");

        try (var testProject = builder.addFileContents(
                        """
                        from ..base import *

                        class Child(BaseClass):
                            pass
                        """,
                        "pkg/sub/child.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var childFile = AnalyzerUtil.getFileFor(analyzer, "pkg.sub.Child").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(childFile);

            assertTrue(
                    resolvedImports.stream().anyMatch(cu -> cu.identifier().equals("BaseClass")),
                    "Should resolve BaseClass from relative wildcard import");
        }
    }

    @Test
    public void testLastImportWins_RelativeWildcardAfterExplicit() throws IOException {
        // Last import wins applies to relative wildcards too
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg/__init__.py")
                .addFileContents("# Package marker\n", "pkg/sub/__init__.py")
                .addFileContents(
                        """
                        class Target:
                            pass
                        """,
                        "pkg/explicit.py")
                .addFileContents(
                        """
                        class Target:
                            pass
                        """,
                        "pkg/wildcard.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg.explicit import Target  # explicit import (first)
                        from ..wildcard import *         # relative wildcard (second - wins)

                        class Consumer:
                            pass
                        """,
                        "pkg/sub/consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "pkg.sub.Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var targetCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Target"))
                    .collect(Collectors.toList());

            assertEquals(1, targetCUs.size(), "Should resolve only one 'Target' class");
            assertEquals(
                    "pkg.Target",
                    targetCUs.getFirst().fqName(),
                    "Last import wins: relative wildcard after explicit should shadow the explicit");
        }
    }

    @Test
    public void testWildcardImportFromPackageInit() throws IOException {
        // Test that wildcard imports can find exports in __init__.py
        // This tests the __init__.py fallback when no module.py exists
        var builder = InlineTestProjectCreator.code(
                        """
                        class PackageClass:
                            pass

                        def package_function():
                            pass
                        """,
                        "mypkg/__init__.py")
                .addFileContents("# Package marker\n", "mypkg/subpkg/__init__.py");

        try (var testProject = builder.addFileContents(
                        """
                        from mypkg import *

                        class Consumer:
                            pass
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var importedNames =
                    resolvedImports.stream().map(cu -> cu.identifier()).collect(Collectors.toSet());

            assertTrue(importedNames.contains("PackageClass"), "Should import PackageClass from __init__.py");
            assertTrue(importedNames.contains("package_function"), "Should import package_function from __init__.py");
        }
    }

    @Test
    public void testWildcardImportsPublicFunctions() throws IOException {
        // Wildcard imports should include public functions (not just classes)
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg/__init__.py")
                .addFileContents(
                        """
                        class PublicClass:
                            pass

                        def public_function():
                            pass

                        def _private_function():
                            pass

                        def __dunder_function():
                            pass
                        """,
                        "pkg/module.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg.module import *

                        class Consumer:
                            pass
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var importedNames =
                    resolvedImports.stream().map(cu -> cu.identifier()).collect(Collectors.toSet());

            assertTrue(importedNames.contains("PublicClass"), "Should import PublicClass");
            assertTrue(importedNames.contains("public_function"), "Should import public_function");
            assertFalse(
                    importedNames.contains("_private_function"),
                    "Should NOT import _private_function (underscore prefix)");
            assertFalse(
                    importedNames.contains("__dunder_function"),
                    "Should NOT import __dunder_function (underscore prefix)");
        }
    }
}
