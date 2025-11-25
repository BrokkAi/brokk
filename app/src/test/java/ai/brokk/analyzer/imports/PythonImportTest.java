package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class PythonImportTest {

    @Test
    public void testExplicitImportHasPrecedenceOverWildcard() throws IOException {
        // Setup: Two packages with same-named class, consumer imports explicit from pkg1 and wildcard from pkg2
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
                        from pkg1.ambiguous import Ambiguous  # explicit import
                        from pkg2.ambiguous import *          # wildcard import

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
                    "pkg1.Ambiguous",
                    ambiguousCUs.getFirst().fqName(),
                    "Explicitly imported class should be chosen over wildcard");
        }
    }

    @Test
    public void testAmbiguousWildcardImportsAreResolvedDeterministically() throws IOException {
        // Setup: Two packages with same-named class, consumer imports wildcards from both
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
                        from pkg2.ambiguous import *  # second wildcard

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
                    "pkg1.Ambiguous",
                    ambiguousCUs.getFirst().fqName(),
                    "First wildcard import should win for ambiguous simple names");
        }
    }

    @Test
    public void testWildcardImportsPublicClassesOnly() throws IOException {
        // Setup: Module with public and private classes
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
            assertTrue(!importedNames.contains("_PrivateClass"), "Should NOT import _PrivateClass (underscore prefix)");
            assertTrue(!importedNames.contains("__DunderClass"), "Should NOT import __DunderClass (underscore prefix)");
        }
    }

    @Test
    public void testWildcardWithRelativeImport() throws IOException {
        // Setup: Package with subpackage, using relative wildcard import
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
    public void testExplicitImportWinsOverRelativeWildcard() throws IOException {
        // Setup: Explicit import from one module, relative wildcard from another with same-named class
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
                        from pkg.explicit import Target  # explicit import
                        from ..wildcard import *         # relative wildcard

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
                    "pkg.Target", targetCUs.getFirst().fqName(), "Explicit import should win over relative wildcard");
        }
    }
}
