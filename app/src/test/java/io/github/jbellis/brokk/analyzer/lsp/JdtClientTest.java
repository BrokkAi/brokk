package io.github.jbellis.brokk.analyzer.lsp;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.JdtClient;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdtClientTest {

    private static final Logger logger = LoggerFactory.getLogger(JdtClientTest.class);

    @Nullable
    private static JdtClient client = null;

    private static IProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        testProject = createTestProject("testcode-java");
        logger.debug(
                "Setting up analyzer with test code from {}",
                testProject.getRoot().toAbsolutePath().normalize());
        client = new JdtClient(testProject);
    }

    public static IProject createTestProject(String subDir) {
        var testDir = Path.of("./src/test/resources", subDir);
        assertTrue(Files.exists(testDir), String.format("Test resource dir missing: %s", testDir));
        assertTrue(Files.isDirectory(testDir), String.format("%s is not a directory", testDir));

        return new IProject() {
            @Override
            public Path getRoot() {
                return testDir.toAbsolutePath();
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                var files = testDir.toFile().listFiles();
                if (files == null) {
                    return Collections.emptySet();
                }
                return Arrays.stream(files)
                        .map(file -> new ProjectFile(testDir, file.toPath()))
                        .collect(Collectors.toSet());
            }
        };
    }

    @AfterAll
    public static void teardown() {
        if (client != null) {
            client.close();
        }
        try {
            testProject.close();
        } catch (Exception e) {
            logger.error("Exception encountered while closing the test project at the end of testing", e);
        }
    }

    @Test
    public void isClassInProjectTest() {
        assert (client.isClassInProject("A"));

        assert (!client.isClassInProject("java.nio.filename.Path"));
        assert (!client.isClassInProject("org.foo.Bar"));
    }

    @Test
    public void sanitizeTypeTest() {
        // Simple types
        assertEquals("String", client.sanitizeType("java.lang.String"));
        assertEquals("String[]", client.sanitizeType("java.lang.String[]"));

        // Generic types
        assertEquals(
                "Function<Integer, Integer>",
                client.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>"));

        // Nested generic types
        assertEquals(
                "Map<String, List<Integer>>",
                client.sanitizeType("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>"));

        // Method return type with generics
        assertEquals(
                "Function<Integer, Integer>",
                client.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>"));
    }

    @Test
    public void getAllClassesTest() {
        final var classes = client.getAllDeclarations().stream()
                .map(CodeUnit::fqName)
                .sorted()
                .toList();
        final var expected = List.of(
                "A",
                "A.AInner",
                "A.AInner.AInnerInner",
                "A.AInnerStatic",
                "AnnotatedClass",
                "AnnotatedClass.InnerHelper",
                "AnonymousUsage",
                "AnonymousUsage.NestedClass",
                "B",
                "BaseClass",
                "C",
                "C.Foo",
                "CamelClass",
                "CustomAnnotation",
                "CyclicMethods",
                "D",
                "D.DSub",
                "D.DSubStatic",
                "E",
                "EnumClass",
                "F",
                "Foo",
                "Interface",
                "MethodReturner",
                "ServiceImpl",
                "ServiceInterface",
                "UseE",
                "UsePackaged",
                "XExtendsY");
        assertEquals(expected, classes);
    }

    @Test
    public void getCallgraphToTest() {
        final var callgraph = client.getCallgraphTo("A.method1", 5);

        // Expect A.method1 -> [B.callsIntoA, D.methodD1]
        assertTrue(callgraph.containsKey("A.method1"), "Should contain A.method1 as a key");

        final var callers = callgraph.getOrDefault("A.method1", Collections.emptyList()).stream()
                .map(site -> site.target().fqName())
                .collect(Collectors.toSet());
        assertEquals(Set.of("B.callsIntoA", "D.methodD1"), callers);
    }

    @Test
    public void getCallgraphFromTest() {
        final var callgraph = client.getCallgraphFrom("B.callsIntoA", 5);

        // Expect B.callsIntoA -> [A.method1, A.method2]
        assertTrue(callgraph.containsKey("B.callsIntoA"), "Should contain B.callsIntoA as a key");

        final var callees = callgraph.getOrDefault("B.callsIntoA", Collections.emptyList()).stream()
                .map(site -> site.target().fqName())
                .collect(Collectors.toSet());
        assertTrue(callees.contains("A.method1"), "Should call A.method1");
        assertTrue(callees.contains("A.method2"), "Should call A.method2");
    }

    @Test
    public void getDeclarationsInFileTest() {
        final var maybeFile = client.getFileFor("D");
        assertTrue(maybeFile.isPresent());
        final var file = maybeFile.get();
        final var classes = client.getDeclarationsInFile(file);

        final var expected = Set.of(
                // Classes
                CodeUnit.cls(file, "", "D"),
                CodeUnit.cls(file, "", "D.DSub"),
                CodeUnit.cls(file, "", "D.DSubStatic"),
                // Methods
                CodeUnit.fn(file, "", "D.methodD1"),
                CodeUnit.fn(file, "", "D.methodD2"),
                // Fields
                CodeUnit.field(file, "", "D.field1"),
                CodeUnit.field(file, "", "D.field2"));
        assertEquals(expected, classes);
    }

    @Test
    public void declarationsInPackagedFileTest() {
        final var file = new ProjectFile(client.getProjectRoot(), "Packaged.java");
        final var declarations = client.getDeclarationsInFile(file);
        final var expected = Set.of(
                // Class
                CodeUnit.cls(file, "io.github.jbellis.brokk", "Foo"),
                // Method
                CodeUnit.fn(file, "io.github.jbellis.brokk", "Foo.bar")
                // No fields in Packaged.java
                );
        assertEquals(expected, declarations);
    }

    @Test
    public void getUsesMethodExistingTest() {
        final var symbol = "A.method2";
        final var usages = client.getUses(symbol);

        // Expect references in B.callsIntoA() because it calls a.method2("test")
        final var actualRefs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(Set.of("B.callsIntoA", "AnonymousUsage.foo$anon$5:12"), actualRefs);
    }

    @Test
    public void getUsesNestedClassConstructorTest() {
        final var symbol = "A$AInner.AInner";
        final var usages = client.getUses(symbol);

        // Expect references in B.callsIntoA() because it calls a.method2("test")
        final var actualRefs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(Set.of("A.usesInnerClass"), actualRefs);
    }

    @Test
    public void getUsesMethodNonexistentTest() {
        final var symbol = "A.noSuchMethod:java.lang.String()";
        final var ex = assertThrows(IllegalArgumentException.class, () -> client.getUses(symbol));
        assertTrue(ex.getMessage().contains("not found as a method, field, or class"));
    }

    @Test
    public void getUsesFieldExistingTest() {
        final var symbol = "D.field1"; // fully qualified field name
        final var usages = client.getUses(symbol);

        final var actualRefs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(Set.of("D.methodD2", "E.dMethod"), actualRefs);
    }

    @Test
    public void getUsesFieldNonexistentTest() {
        final var symbol = "D.notAField";
        final var ex = assertThrows(IllegalArgumentException.class, () -> client.getUses(symbol));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    public void getUsesFieldFromUseETest() {
        final var symbol = "UseE.e";
        final var usages = client.getUses(symbol);
        final var refs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(Set.of("UseE.moreM", "UseE.moreF"), refs);
    }

    @Test
    public void getUsesClassBasicTest() {
        // “A” has no static members, but it is used as a local type in B.callsIntoA and D.methodD1
        final var symbol = "A";
        final var usages = client.getUses(symbol);

        // References to A include both function references and local variable references
        final var foundRefs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        // Get the usages of each type
        final var functionRefs = usages.stream()
                .filter(CodeUnit::isFunction)
                .map(CodeUnit::fqName)
                .collect(Collectors.toSet());
        final var fieldRefs = usages.stream()
                .filter(cu -> !cu.isFunction() && !cu.isClass())
                .map(CodeUnit::fqName)
                .collect(Collectors.toSet());
        final var classRefs =
                usages.stream().filter(CodeUnit::isClass).map(CodeUnit::fqName).collect(Collectors.toSet());

        // There should be function usages in these methods
        assertEquals(
                Set.of(
                        "B.callsIntoA", // Both methods and constructor
                        "D.methodD1", // Both methods and constructor
                        "AnonymousUsage.foo$anon$5:12", // calls method
                        "A.method5", // invokes constructor
                        "A.method6$anon$32:12" // invokes constructor
                        ),
                functionRefs);

        // Ensure we have the correct usage types with our refactored implementation
        final var all = new HashSet<String>();
        all.addAll(functionRefs);
        all.addAll(fieldRefs);
        all.addAll(classRefs);
        assertEquals(foundRefs, all);
    }

    @Test
    public void getUsesClassNonexistentTest() {
        final var symbol = "NoSuchClass";
        final var ex = assertThrows(IllegalArgumentException.class, () -> client.getUses(symbol));
        assertTrue(ex.getMessage()
                .contains("Symbol 'NoSuchClass' (resolved: 'NoSuchClass') not found as a method, field, or class"));
    }

    @Test
    public void getUsesNestedClassTest() {
        final var symbol = "A$AInner";
        final var usages = client.getUses(symbol);
        assertEquals(
                Set.of("A.usesInnerClass"),
                usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet()));
    }

    @Test
    public void testGetDefinitionForClass() {
        var classDDef = client.getDefinition("D");
        assertTrue(classDDef.isPresent(), "Should find definition for class 'D'");
        assertEquals("D", classDDef.get().fqName());
        assertTrue(classDDef.get().isClass());
    }

    @Test
    public void testGetDefinitionForMethod() {
        var method1Def = client.getDefinition("A.method1");
        assertTrue(method1Def.isPresent(), "Should find definition for method 'A.method1'");
        assertEquals("A.method1", method1Def.get().fqName());
        assertTrue(method1Def.get().isFunction());
    }

    @Test
    @Disabled("JDT LSP does not index field symbols")
    public void testGetDefinitionForField() {
        var field1Def = client.getDefinition("D.field1");
        assertTrue(field1Def.isPresent(), "Should find definition for field 'D.field1'");
        assertEquals("D.field1", field1Def.get().fqName());
        assertFalse(field1Def.get().isClass());
        assertFalse(field1Def.get().isFunction());
    }

    @Test
    public void testGetDefinitionNonExistent() {
        var nonExistentDef = client.getDefinition("NonExistentSymbol");
        assertFalse(nonExistentDef.isPresent(), "Should not find definition for non-existent symbol");
    }

    @Test
    public void getUsesClassWithStaticMembersTest() {
        final var symbol = "E";
        final var usages = client.getUses(symbol);

        final var refs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        // Now includes field reference UseE.e as a FIELD type
        // fixme: does not include fields in the initial source for the query
        assertEquals(Set.of("UseE.some", "UseE.e"), refs);
    }

    @Test
    public void getUsesClassInheritanceTest() {
        final var symbol = "BaseClass";
        final var usages = client.getUses(symbol);

        final var refs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        // Get references by type
        final var classRefs =
                usages.stream().filter(CodeUnit::isClass).map(CodeUnit::fqName).collect(Collectors.toSet());

        // Create an error message capturing actual usages
        final var errorMsg = "Expected XExtendsY to be a usage of BaseClass. Actual usages: " + String.join(", ", refs);

        // XExtendsY should show up in the results because it extends BaseClass
        assertTrue(refs.stream().anyMatch(name -> name.contains("XExtendsY")), errorMsg);

        // Verify that XExtendsY is specifically a CLASS type reference
        final var classErrorMsg =
                "Expected XExtendsY to be a CLASS type usage. Class references: " + String.join(", ", classRefs);
        assertTrue(classRefs.stream().anyMatch(name -> name.contains("XExtendsY")), classErrorMsg);

        // New test: Methods returning BaseClass should be included (e.g. MethodReturner.getBase)
        assertTrue(
                refs.stream().anyMatch(name -> name.contains("MethodReturner.getBase")),
                "Expected MethodReturner.getBase to be included in BaseClass usages");
    }
}
