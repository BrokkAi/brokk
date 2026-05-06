package ai.brokk.analyzer.types;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.analyzer.TypeHierarchyProvider;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.CoreTestProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestCodeProject;
import ai.brokk.testutil.TestContextManager;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public final class PythonTypeHierarchyTest {

    @Nullable
    private static CoreTestProject project;

    @Nullable
    private static PythonAnalyzer analyzer;

    @BeforeAll
    public static void setup() {
        project = TestCodeProject.fromResourceDir("testcode-py", Languages.PYTHON);
        analyzer = new PythonAnalyzer(project);
    }

    @AfterAll
    public static void teardown() {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void testPythonTypeHierarchy() {
        // Test parent class resolution for Python
        var testAnalyzer = analyzer;
        assertNotNull(testAnalyzer);
        assertNotNull(project);

        // Test 1: Simple same-file inheritance
        ProjectFile simplePy = new ProjectFile(project.getRoot(), "inheritance/simple.py");
        Set<CodeUnit> simpleDecls = testAnalyzer.getDeclarations(simplePy);

        var dogClass =
                simpleDecls.stream().filter(cu -> cu.identifier().equals("Dog")).findFirst();
        assertTrue(dogClass.isPresent(), "Dog class should be found");

        var animalClass = simpleDecls.stream()
                .filter(cu -> cu.identifier().equals("Animal"))
                .findFirst();
        assertTrue(animalClass.isPresent(), "Animal class should be found");

        var dogAncestors = testAnalyzer.getDirectAncestors(dogClass.get());
        assertEquals(1, dogAncestors.size(), "Dog should have exactly 1 direct ancestor (Animal)");
        assertTrue(
                dogAncestors.stream().anyMatch(cu -> cu.identifier().equals("Animal")),
                "Dog's ancestor should be Animal");

        // Test 2: Multi-level inheritance
        ProjectFile multilevelPy = new ProjectFile(project.getRoot(), "inheritance/multilevel.py");
        Set<CodeUnit> multilevelDecls = testAnalyzer.getDeclarations(multilevelPy);

        var childClass = multilevelDecls.stream()
                .filter(cu -> cu.identifier().equals("Child"))
                .findFirst();
        assertTrue(childClass.isPresent(), "Child class should be found");

        var childDirectAncestors = testAnalyzer.getDirectAncestors(childClass.get());
        assertEquals(1, childDirectAncestors.size(), "Child should have exactly 1 direct ancestor (Middle)");

        var childAllAncestors = testAnalyzer.getAncestors(childClass.get());
        assertEquals(2, childAllAncestors.size(), "Child should have 2 transitive ancestors (Middle, Base)");

        // Test 3: Cross-file inheritance
        ProjectFile childPy = new ProjectFile(project.getRoot(), "inheritance/child.py");
        Set<CodeUnit> childFileDecls = testAnalyzer.getDeclarations(childPy);

        var birdClass = childFileDecls.stream()
                .filter(cu -> cu.identifier().equals("Bird"))
                .findFirst();
        assertTrue(birdClass.isPresent(), "Bird class should be found");

        var birdAncestors = testAnalyzer.getDirectAncestors(birdClass.get());
        assertEquals(1, birdAncestors.size(), "Bird should have exactly 1 direct ancestor (Animal)");

        // Test 4: Multiple inheritance
        ProjectFile multiplePy = new ProjectFile(project.getRoot(), "inheritance/multiple.py");
        Set<CodeUnit> multipleDecls = testAnalyzer.getDeclarations(multiplePy);

        var duckClass = multipleDecls.stream()
                .filter(cu -> cu.identifier().equals("Duck"))
                .findFirst();
        assertTrue(duckClass.isPresent(), "Duck class should be found");

        var duckAncestors = testAnalyzer.getDirectAncestors(duckClass.get());
        assertEquals(2, duckAncestors.size(), "Duck should have exactly 2 direct ancestors (Flyable, Swimmable)");
    }

    @Test
    void testRelativeImportSameDirectory() throws Exception {
        var builder = InlineTestProjectCreator.code("# Package marker\n", "mypackage/__init__.py")
                .addFileContents(
                        """
                class ChildClass:
                    def child_method(self):
                        pass
                """,
                        "mypackage/child.py");

        try (var testProject = builder.addFileContents(
                        """
                from .child import ChildClass

                class SiblingClass:
                    def __init__(self):
                        self.child = ChildClass()
                """,
                        "mypackage/sibling.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var siblingFile = new ProjectFile(testProject.getRoot(), "mypackage/sibling.py");
            var imports = analyzer.importedCodeUnitsOf(siblingFile);

            assertTrue(
                    imports.stream().anyMatch(cu -> cu.fqName().equals("mypackage.child.ChildClass")),
                    "Should resolve 'from .child import ChildClass' to mypackage.child.ChildClass");
        }
    }

    @Test
    void decoratedClassDirectAncestorsResolveFromLocalSuperclassList() throws Exception {
        String source =
                """
                def marker(cls):
                    return cls

                class Base:
                    pass

                @marker
                class Child(Base):
                    pass
                """;

        try (var testProject =
                InlineTestProjectCreator.code(source, "decorated.py").build()) {
            var testAnalyzer = new PythonAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "decorated.py");
            CodeUnit child = testAnalyzer.getDeclarations(file).stream()
                    .filter(cu -> cu.identifier().equals("Child"))
                    .findFirst()
                    .orElseThrow();

            var ancestors = testAnalyzer.getDirectAncestors(child);

            assertEquals(1, ancestors.size());
            assertEquals("Base", ancestors.getFirst().identifier());
        }
    }

    @Test
    void testRelativeImportParentDirectory() throws Exception {
        var builder = InlineTestProjectCreator.code("# Package marker\n", "mypackage/__init__.py")
                .addFileContents("# Subpackage marker\n", "mypackage/subdir/__init__.py")
                .addFileContents(
                        """
                class BaseClass:
                    def base_method(self):
                        pass
                """,
                        "mypackage/base.py");

        try (var testProject = builder.addFileContents(
                        """
                from ..base import BaseClass

                class ChildClass(BaseClass):
                    def child_method(self):
                        pass
                """,
                        "mypackage/subdir/child.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var childFile = new ProjectFile(testProject.getRoot(), "mypackage/subdir/child.py");
            var imports = analyzer.importedCodeUnitsOf(childFile);

            assertTrue(
                    imports.stream().anyMatch(cu -> cu.fqName().equals("mypackage.base.BaseClass")),
                    "Should resolve 'from ..base import BaseClass' to mypackage.base.BaseClass");
        }
    }

    @Test
    void testRelativeImportGrandparentDirectory() throws Exception {
        var builder = InlineTestProjectCreator.code("# Package marker\n", "mypackage/__init__.py")
                .addFileContents("# Subpackage marker\n", "mypackage/subdir/__init__.py")
                .addFileContents("# Deep package marker\n", "mypackage/subdir/deep/__init__.py")
                .addFileContents(
                        """
                class TopClass:
                    def top_method(self):
                        pass
                """,
                        "mypackage/top.py");

        try (var testProject = builder.addFileContents(
                        """
                from ...top import TopClass

                class DeepClass(TopClass):
                    def deep_method(self):
                        pass
                """,
                        "mypackage/subdir/deep/nested.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var nestedFile = new ProjectFile(testProject.getRoot(), "mypackage/subdir/deep/nested.py");
            var imports = analyzer.importedCodeUnitsOf(nestedFile);

            assertTrue(
                    imports.stream().anyMatch(cu -> cu.fqName().equals("mypackage.top.TopClass")),
                    "Should resolve 'from ...top import TopClass' to mypackage.top.TopClass");
        }
    }

    @Test
    void testRelativeImportInheritance() throws Exception {
        var builder = InlineTestProjectCreator.code("# Package marker\n", "zoo/__init__.py")
                .addFileContents("# Subpackage marker\n", "zoo/mammals/__init__.py")
                .addFileContents(
                        """
                class Animal:
                    def speak(self):
                        pass
                """,
                        "zoo/animal.py");

        try (var testProject = builder.addFileContents(
                        """
                from ..animal import Animal

                class Dog(Animal):
                    def bark(self):
                        return "woof"
                """,
                        "zoo/mammals/dog.py")
                .build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var dogFile = new ProjectFile(testProject.getRoot(), "zoo/mammals/dog.py");
            var dogDecls = analyzer.getDeclarations(dogFile);

            var dogClass = dogDecls.stream()
                    .filter(cu -> cu.identifier().equals("Dog"))
                    .findFirst();
            assertTrue(dogClass.isPresent(), "Dog class should be found");

            var ancestors =
                    analyzer.as(TypeHierarchyProvider.class).orElseThrow().getDirectAncestors(dogClass.get());
            assertEquals(1, ancestors.size(), "Dog should have exactly 1 direct ancestor (Animal)");
            assertTrue(
                    ancestors.stream().anyMatch(cu -> cu.identifier().equals("Animal")),
                    "Dog should extend Animal via relative import");
        }
    }

    @Test
    void testPackagedFunctionLocalClasses() throws Exception {
        var builder = InlineTestProjectCreator.code("# Package marker\n", "mypackage/__init__.py")
                .addFileContents(
                        """
                def my_function():
                    class LocalClass:
                        pass
                """,
                        "mypackage/packaged_functions.py");

        try (var testProject = builder.build()) {
            var analyzer = new PythonAnalyzer(testProject);
            var file = new ProjectFile(testProject.getRoot(), "mypackage/packaged_functions.py");
            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            var functions = declarations.stream()
                    .filter(cu -> cu.identifier().equals("my_function"))
                    .collect(Collectors.toList());
            assertEquals(1, functions.size(), "Should find exactly 1 my_function");
            CodeUnit myFunction = functions.getFirst();

            var localClasses = declarations.stream()
                    .filter(cu -> cu.fqName().contains("LocalClass"))
                    .collect(Collectors.toList());
            assertEquals(1, localClasses.size(), "Should find exactly 1 LocalClass");
            CodeUnit localClass = localClasses.getFirst();

            var functionChildren = analyzer.getDirectChildren(myFunction);
            assertTrue(
                    functionChildren.stream().anyMatch(cu -> cu.equals(localClass)),
                    "LocalClass should be a child of my_function");
        }
    }

    @Test
    void testPackagedTopLevelClassesIncludeModuleName() {
        var testAnalyzer = analyzer;
        assertNotNull(testAnalyzer);
        assertNotNull(project);

        ProjectFile testUtilsFile = new ProjectFile(project.getRoot(), "tests/units/utils/test_utils.py");
        Set<CodeUnit> declarations = testAnalyzer.getDeclarations(testUtilsFile);

        var topLevelClasses = declarations.stream()
                .filter(CodeUnit::isClass)
                .filter(cu -> !cu.fqName().contains(".test_backend_variable_cls$"))
                .collect(Collectors.toList());

        assertTrue(topLevelClasses.stream()
                .anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils.ExampleTestState")));
        assertTrue(
                topLevelClasses.stream().anyMatch(cu -> cu.fqName().equals("tests.units.utils.test_utils.DataFrame")));
    }

    @Test
    void testDiamondInheritanceSupportingFragments() throws Exception {
        var builder = InlineTestProjectCreator.code(
                        """
                class A:
                    def method_a(self):
                        pass
                """,
                        "diamond/a.py")
                .addFileContents(
                        """
                from .a import A

                class B(A):
                    def method_b(self):
                        pass
                """,
                        "diamond/b.py")
                .addFileContents(
                        """
                from .a import A

                class C(A):
                    def method_c(self):
                        pass
                """,
                        "diamond/c.py")
                .addFileContents(
                        """
                from .b import B
                from .c import C

                class D(B, C):
                    def method_d(self):
                        pass
                """,
                        "diamond/d.py")
                .addFileContents("# Package marker\n", "diamond/__init__.py");

        try (var testProject = builder.build()) {
            var testAnalyzer = new PythonAnalyzer(testProject);
            var cm = new TestContextManager(testProject, testAnalyzer);

            var dClass = testAnalyzer.getDefinitions("diamond.d.D");
            var classD = dClass.getFirst();

            var summaryFragment = new ContextFragments.SummaryFragment(
                    cm, "diamond.d.D", ContextFragment.SummaryType.CODEUNIT_SKELETON);

            var supporting = summaryFragment.supportingFragments();
            var supportingIdentifiers = supporting.stream()
                    .filter(f -> f instanceof ContextFragments.SummaryFragment)
                    .map(f -> ((ContextFragments.SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertEquals(Set.of("diamond.b.B", "diamond.c.C"), supportingIdentifiers);
        }
    }
}
