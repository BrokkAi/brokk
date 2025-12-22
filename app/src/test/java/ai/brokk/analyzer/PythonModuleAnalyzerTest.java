package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PythonModuleAnalyzerTest {

    @Test
    public void moduleCodeUnitCreated_withTopLevelChildren_only() throws IOException {
        String modPy =
                """
                # simple python module
                class A:
                    class Inner:
                        pass
                def f():
                    pass
                x = 1
                """;

        try (IProject project = InlineTestProjectCreator.code(modPy, "mod.py").build()) {

            var analyzer = createTreeSitterAnalyzer(project);

            var moduleOpt = analyzer.getDefinitions("mod").stream()
                    .filter(CodeUnit::isModule)
                    .findFirst();

            assertTrue(moduleOpt.isPresent(), "Expected a module CodeUnit for 'mod'");
            var module = moduleOpt.get();

            var childFqns = analyzer.getDirectChildren(module).stream()
                    .map(CodeUnit::fqName)
                    .sorted()
                    .toList();

            assertEquals(List.of("mod.A", "mod.f", "mod.x"), childFqns);
        }
    }

    @Test
    public void moduleCodeUnitCreated_forInitPy_packageName() throws IOException {
        String initPy =
                """
                class A:
                    pass
                def f():
                    pass
                """;

        try (IProject project =
                InlineTestProjectCreator.code(initPy, "pkg/__init__.py").build()) {

            var analyzer = createTreeSitterAnalyzer(project);

            var moduleOpt = analyzer.getDefinitions("pkg").stream()
                    .filter(CodeUnit::isModule)
                    .findFirst();

            assertTrue(moduleOpt.isPresent(), "Expected a module CodeUnit for 'pkg' from __init__.py");
            var module = moduleOpt.get();

            var childFqns = analyzer.getDirectChildren(module).stream()
                    .map(CodeUnit::fqName)
                    .sorted()
                    .toList();

            assertEquals(List.of("pkg.A", "pkg.f"), childFqns);
        }
    }

    @Test
    public void moduleCodeUnitsArePerFile_inPackagedDirectory() throws IOException {
        String aPy = """
                class A:
                    pass
                """;
        String bPy = """
                def f():
                    pass
                """;

        try (IProject project = InlineTestProjectCreator.code(aPy, "pkg/a.py")
                .addFileContents(bPy, "pkg/b.py")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(project);

            var modAOpt = analyzer.getDefinitions("pkg.a").stream()
                    .filter(CodeUnit::isModule)
                    .findFirst();
            var modBOpt = analyzer.getDefinitions("pkg.b").stream()
                    .filter(CodeUnit::isModule)
                    .findFirst();

            assertTrue(modAOpt.isPresent(), "Expected a module CodeUnit for 'pkg.a'");
            assertTrue(modBOpt.isPresent(), "Expected a module CodeUnit for 'pkg.b'");

            var modA = modAOpt.get();
            var modB = modBOpt.get();

            var childrenA = analyzer.getDirectChildren(modA).stream()
                    .map(CodeUnit::fqName)
                    .sorted()
                    .toList();
            var childrenB = analyzer.getDirectChildren(modB).stream()
                    .map(CodeUnit::fqName)
                    .sorted()
                    .toList();

            assertEquals(List.of("pkg.a.A"), childrenA);
            assertEquals(List.of("pkg.b.f"), childrenB);
        }
    }
}
