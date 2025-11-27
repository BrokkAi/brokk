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
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class");
            assertEquals(
                    "pkg2.ambiguous$Ambiguous",
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
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class");
            assertEquals(
                    "pkg2.ambiguous$Ambiguous",
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
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var ambiguousCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Ambiguous"))
                    .collect(Collectors.toList());

            assertEquals(1, ambiguousCUs.size(), "Should resolve only one 'Ambiguous' class from wildcards");
            assertEquals(
                    "pkg2.ambiguous$Ambiguous",
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
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
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
            var childFile =
                    AnalyzerUtil.getFileFor(analyzer, "pkg.sub.child$Child").get();
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
            var consumerFile = AnalyzerUtil.getFileFor(analyzer, "pkg.sub.consumer$Consumer")
                    .get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var targetCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("Target"))
                    .collect(Collectors.toList());

            assertEquals(1, targetCUs.size(), "Should resolve only one 'Target' class");
            assertEquals(
                    "pkg.wildcard$Target",
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
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var importedNames =
                    resolvedImports.stream().map(cu -> cu.identifier()).collect(Collectors.toSet());

            assertTrue(importedNames.contains("PackageClass"), "Should import PackageClass from __init__.py");
            assertTrue(importedNames.contains("package_function"), "Should import package_function from __init__.py");
        }
    }

    @Test
    public void testInitPyClassFqName() throws IOException {
        // Test the FQN of a class defined in __init__.py
        // This documents/validates the current mypackage.__init__$ClassName representation
        var builder = InlineTestProjectCreator.code(
                """
                class InitClass:
                    def method(self):
                        pass
                """,
                "mypackage/__init__.py");

        try (var testProject = builder.build()) {
            var analyzer = new PythonAnalyzer(testProject);

            // Find the InitClass code unit by trying the expected FQN pattern
            var initClassDefinitions = analyzer.getDefinitions("mypackage.__init__$InitClass");
            assertEquals(1, initClassDefinitions.size(), "Should find exactly one InitClass defined in __init__.py");

            var initClass = initClassDefinitions.iterator().next();
            // Document the current FQN representation
            assertEquals(
                    "mypackage.__init__$InitClass",
                    initClass.fqName(),
                    "Class in __init__.py should have FQN: package.__init__$ClassName");
            assertEquals("InitClass", initClass.identifier(), "identifier() should return simple class name");
        }
    }

    @Test
    public void testFromPackageImportClassName() throws IOException {
        // Test: from mypackage import ClassName where ClassName is in __init__.py
        // Verifies resolveImports can find it when referenced this way
        var builder = InlineTestProjectCreator.code(
                """
                class InitClass:
                    pass
                """,
                "mypackage/__init__.py");

        try (var testProject = builder.addFileContents(
                        """
                        from mypackage import InitClass

                        class Consumer:
                            def use_it(self):
                                obj = InitClass()
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var initClassImports = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("InitClass"))
                    .collect(Collectors.toList());

            assertEquals(1, initClassImports.size(), "Should resolve InitClass from 'from mypackage import InitClass'");
            assertEquals(
                    "mypackage.__init__$InitClass",
                    initClassImports.getFirst().fqName(),
                    "'from mypackage import InitClass' should resolve to class in __init__.py");
        }
    }

    @Test
    public void testImportPackageAttributeAccess() throws IOException {
        // Test: import mypackage followed by mypackage.ClassName usage
        // This pattern uses attribute access rather than direct import
        var builder = InlineTestProjectCreator.code(
                """
                class InitClass:
                    pass
                """,
                "mypackage/__init__.py");

        try (var testProject = builder.addFileContents(
                        """
                        import mypackage

                        class Consumer:
                            def use_it(self):
                                obj = mypackage.InitClass()
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();

            // For 'import mypackage' style, resolveImports only tracks the import statement itself
            // The attribute access mypackage.InitClass is NOT resolved via importedCodeUnitsOf -
            // it's a different kind of reference (qualified name lookup, not import resolution)
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            // Document expected behavior: 'import mypackage' does not bring InitClass into
            // the resolved imports - only 'from mypackage import InitClass' would do that
            // The class IS accessible at runtime via mypackage.InitClass, but that's not import resolution
            assertTrue(resolvedImports.isEmpty(),
                    "import mypackage style does not resolve to specific symbols - " +
                    "use 'from mypackage import ClassName' for import resolution");

            // Verify the class itself still has the correct FQN
            var initClassDefinitions = analyzer.getDefinitions("mypackage.__init__$InitClass");
            assertEquals(1, initClassDefinitions.size(), "InitClass should still be findable by FQN");
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
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
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

    @Test
    public void testRelativePackageWildcardFromInit() throws IOException {
        // Test: from .. import * should import from parent package's __init__.py
        var builder = InlineTestProjectCreator.code(
                        """
                        class ParentClass:
                            pass

                        class _PrivateParentClass:
                            pass

                        def parent_function():
                            pass
                        """,
                        "pkg/__init__.py")
                .addFileContents("# Subpackage marker\n", "pkg/sub/__init__.py");

        try (var testProject = builder.addFileContents(
                        """
                        from .. import *

                        class Child:
                            pass
                        """,
                        "pkg/sub/child.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var childFile =
                    AnalyzerUtil.getFileFor(analyzer, "pkg.sub.child$Child").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(childFile);

            var importedNames =
                    resolvedImports.stream().map(cu -> cu.identifier()).collect(Collectors.toSet());

            assertTrue(importedNames.contains("ParentClass"), "Should import ParentClass from parent __init__.py");
            assertTrue(
                    importedNames.contains("parent_function"), "Should import parent_function from parent __init__.py");
            assertFalse(
                    importedNames.contains("_PrivateParentClass"),
                    "Should NOT import _PrivateParentClass (underscore prefix)");
        }
    }

    @Test
    public void testAliasLastWins() throws IOException {
        // Test: from pkg1.m import A as X then from pkg2.m import A as X
        // Last import wins on the alias name X
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg1/__init__.py")
                .addFileContents("# Package marker\n", "pkg2/__init__.py")
                .addFileContents(
                        """
                        class A:
                            pass
                        """,
                        "pkg1/m.py")
                .addFileContents(
                        """
                        class A:
                            pass
                        """,
                        "pkg2/m.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg1.m import A as X  # first
                        from pkg2.m import A as X  # second - wins

                        class Consumer:
                            pass
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            // Note: Current implementation resolves the original name (A), not the alias (X)
            // This test documents the expected behavior - last import wins
            var aCUs = resolvedImports.stream()
                    .filter(cu -> cu.identifier().equals("A"))
                    .collect(Collectors.toList());

            assertEquals(1, aCUs.size(), "Should resolve only one 'A' class (last wins)");
            assertEquals(
                    "pkg2.m$A",
                    aCUs.getFirst().fqName(),
                    "Last import wins: second aliased import should shadow the first");
        }
    }

    @Test
    public void testCurrentPackageWildcard() throws IOException {
        // Test: from . import * within a package should import from current package's __init__.py
        var builder = InlineTestProjectCreator.code(
                """
                        class SiblingClass:
                            pass

                        def sibling_function():
                            pass
                        """,
                "pkg/__init__.py");

        try (var testProject = builder.addFileContents(
                        """
                        from . import *

                        class Consumer:
                            pass
                        """,
                        "pkg/consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "pkg.consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var importedNames =
                    resolvedImports.stream().map(cu -> cu.identifier()).collect(Collectors.toSet());

            assertTrue(
                    importedNames.contains("SiblingClass"),
                    "Should import SiblingClass from current package __init__.py");
            assertTrue(
                    importedNames.contains("sibling_function"),
                    "Should import sibling_function from current package __init__.py");
        }
    }

    @Test
    public void testModuleAndPackageWildcardCoexistence() throws IOException {
        // Test: from pkg import module as m followed by from pkg.module import *
        // Verify wildcard expansion works correctly with package vs module handling
        var builder = InlineTestProjectCreator.code("# Package marker\n", "pkg/__init__.py")
                .addFileContents(
                        """
                        class ModuleClass:
                            pass

                        def module_function():
                            pass
                        """,
                        "pkg/module.py");

        try (var testProject = builder.addFileContents(
                        """
                        from pkg import module as m
                        from pkg.module import *

                        class Consumer:
                            pass
                        """,
                        "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var consumerFile =
                    AnalyzerUtil.getFileFor(analyzer, "consumer$Consumer").get();
            var resolvedImports = analyzer.importedCodeUnitsOf(consumerFile);

            var importedNames =
                    resolvedImports.stream().map(cu -> cu.identifier()).collect(Collectors.toSet());

            assertTrue(importedNames.contains("ModuleClass"), "Should import ModuleClass from wildcard");
            assertTrue(importedNames.contains("module_function"), "Should import module_function from wildcard");
        }
    }
}
