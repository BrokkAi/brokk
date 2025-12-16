package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public final class PythonDecoratorsTest {

    @Test
    void testDecoratedTopLevelFunctionAndClass() throws Exception {
        String py =
                """
                def deco1(x):
                    return x

                def class_deco(x):
                    return x

                @deco1
                def top_func():
                    pass

                @class_deco
                class TopClass:
                    @deco1
                    def method(self):
                        pass

                    @staticmethod
                    @deco1
                    def static_m():
                        pass
                """;
        try (var project = InlineTestProjectCreator.code(py, "decorators.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            assertNotNull(analyzer, "Analyzer should be initialized");
            assertNotNull(project, "Project should be initialized");

            ProjectFile file = new ProjectFile(project.getRoot(), "decorators.py");
            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            // Top-level decorated function
            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.top_func")),
                    () -> "Missing decorators.top_func. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            // Top-level decorated class
            assertTrue(
                    declarations.stream().filter(CodeUnit::isClass).anyMatch(cu -> cu.fqName()
                            .equals("decorators.TopClass")),
                    () -> "Missing decorators.TopClass. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            // Methods inside TopClass
            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.TopClass.method")),
                    () -> "Missing decorators.TopClass.method. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.TopClass.static_m")),
                    () -> "Missing decorators.TopClass.static_m. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));
        }
    }

    @Test
    void testDecoratedNestedInFunctionAndClass() throws Exception {
        String py =
                """
                def deco1(x):
                    return x

                def class_deco(x):
                    return x

                def outer():
                    @class_deco
                    class Inner:
                        @deco1
                        def im(self):
                            pass
                    return Inner
                """;
        try (var project = InlineTestProjectCreator.code(py, "decorators.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            assertNotNull(analyzer, "Analyzer should be initialized");
            assertNotNull(project, "Project should be initialized");

            ProjectFile file = new ProjectFile(project.getRoot(), "decorators.py");
            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            // Decorated class nested inside a top-level function
            assertTrue(
                    declarations.stream().filter(CodeUnit::isClass).anyMatch(cu -> cu.fqName()
                            .equals("decorators.outer$Inner")),
                    () -> "Missing decorators.outer$Inner. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            // Decorated method inside the nested class
            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.outer$Inner.im")),
                    () -> "Missing decorators.outer$Inner.im. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));
        }
    }

    @Test
    void testDecoratedDeclarationsInTopLevelConditional() throws Exception {
        String py =
                """
                def deco1(x):
                    return x

                def class_deco(x):
                    return x

                if True:
                    @deco1
                    def cond_func():
                        pass

                    @class_deco
                    class CondClass:
                        pass
                """;
        try (var project = InlineTestProjectCreator.code(py, "decorators.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            assertNotNull(analyzer, "Analyzer should be initialized");
            assertNotNull(project, "Project should be initialized");

            ProjectFile file = new ProjectFile(project.getRoot(), "decorators.py");
            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            // Decorated function inside top-level if
            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.cond_func")),
                    () -> "Missing decorators.cond_func. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            // Decorated class inside top-level if
            assertTrue(
                    declarations.stream().filter(CodeUnit::isClass).anyMatch(cu -> cu.fqName()
                            .equals("decorators.CondClass")),
                    () -> "Missing decorators.CondClass. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));
        }
    }

    @Test
    void testDoubleDecoratedTopLevelFunctionAndClass() throws Exception {
        String py =
                """
                def deco1(x):
                    return x

                def deco2(x):
                    return x

                @deco1
                @deco2
                def top_func():
                    pass

                @deco1
                @deco2
                class TopClass:
                    pass
                """;
        try (var project = InlineTestProjectCreator.code(py, "decorators.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            assertNotNull(analyzer, "Analyzer should be initialized");
            assertNotNull(project, "Project should be initialized");

            ProjectFile file = new ProjectFile(project.getRoot(), "decorators.py");
            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.top_func")),
                    () -> "Missing decorators.top_func. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            assertTrue(
                    declarations.stream().filter(CodeUnit::isClass).anyMatch(cu -> cu.fqName()
                            .equals("decorators.TopClass")),
                    () -> "Missing decorators.TopClass. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));
        }
    }

    @Test
    void testDoubleDecoratedDeclarationsInTopLevelConditional() throws Exception {
        String py =
                """
                def deco1(x):
                    return x

                def deco2(x):
                    return x

                if True:
                    @deco1
                    @deco2
                    def cond_func():
                        pass

                    @deco1
                    @deco2
                    class CondClass:
                        pass
                """;
        try (var project = InlineTestProjectCreator.code(py, "decorators.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            assertNotNull(analyzer, "Analyzer should be initialized");
            assertNotNull(project, "Project should be initialized");

            ProjectFile file = new ProjectFile(project.getRoot(), "decorators.py");
            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.cond_func")),
                    () -> "Missing decorators.cond_func. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            assertTrue(
                    declarations.stream().filter(CodeUnit::isClass).anyMatch(cu -> cu.fqName()
                            .equals("decorators.CondClass")),
                    () -> "Missing decorators.CondClass. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));
        }
    }

    @Test
    void testDoubleDecoratedNestedClassInFunction() throws Exception {
        String py =
                """
                def deco1(x):
                    return x

                def deco2(x):
                    return x

                def outer():
                    @deco1
                    @deco2
                    class Inner:
                        def im(self):
                            pass
                    return Inner
                """;
        try (var project = InlineTestProjectCreator.code(py, "decorators.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            assertNotNull(analyzer, "Analyzer should be initialized");
            assertNotNull(project, "Project should be initialized");

            ProjectFile file = new ProjectFile(project.getRoot(), "decorators.py");
            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            assertTrue(
                    declarations.stream().filter(CodeUnit::isClass).anyMatch(cu -> cu.fqName()
                            .equals("decorators.outer$Inner")),
                    () -> "Missing decorators.outer$Inner. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));

            assertTrue(
                    declarations.stream().filter(CodeUnit::isFunction).anyMatch(cu -> cu.fqName()
                            .equals("decorators.outer$Inner.im")),
                    () -> "Missing decorators.outer$Inner.im. Found: "
                            + declarations.stream()
                                    .map(CodeUnit::fqName)
                                    .sorted()
                                    .collect(Collectors.joining(", ")));
        }
    }
}
