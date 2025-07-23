package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.jbellis.brokk.testutil.TestProject.createTestProject;
import static org.junit.jupiter.api.Assertions.*;

public class PhpAnalyzerTest {

    private static IProject testProject;
    private static PhpAnalyzer analyzer;

    @BeforeAll
    static void setUp() throws IOException {
        testProject = createTestProject("testcode-php", Language.PHP);
        analyzer = new PhpAnalyzer(testProject); // This will trigger analysis
    }

    @Test
    void testInitialization() {
        assertNotNull(analyzer, "Analyzer should be initialized.");
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed PHP files.");
    }

    @Test
    void testDeterminePackageName() {
        ProjectFile fooFile = new ProjectFile(testProject.getRoot(), "Foo.php");
        // Accessing internal state for verification, ideally use public API if available
        // For now, check through a CU from that file
        Optional<CodeUnit> fooClassCU = analyzer.getDefinition("My.Lib.Foo");
        assertTrue(fooClassCU.isPresent(), "Foo class CU should be present.");
        assertEquals("My.Lib", fooClassCU.get().packageName(), "Package name for Foo.php should be My.Lib");

        ProjectFile barFile = new ProjectFile(testProject.getRoot(), "Namespaced/Bar.php");
        Optional<CodeUnit> barClassCU = analyzer.getDefinition("Another.SubNs.Bar");
        assertTrue(barClassCU.isPresent(), "Bar class CU should be present.");
        assertEquals("Another.SubNs", barClassCU.get().packageName(), "Package name for Bar.php should be Another.SubNs");

        ProjectFile noNsFile = new ProjectFile(testProject.getRoot(), "NoNamespace.php");
        Optional<CodeUnit> noNsClassCU = analyzer.getDefinition("NoNsClass"); // No package prefix
        assertTrue(noNsClassCU.isPresent(), "NoNsClass CU should be present.");
        assertEquals("", noNsClassCU.get().packageName(), "Package name for NoNamespace.php should be empty.");
    }

    @Test
    void testGetDeclarationsInFile_Foo() {
        ProjectFile fooFile = new ProjectFile(testProject.getRoot(), "Foo.php");
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(fooFile);

        Set<String> expectedFqNames = Set.of(
                "My.Lib.Foo",
                "My.Lib.Foo.MY_CONST",
                "My.Lib.Foo.staticProp",
                "My.Lib.Foo.value",
                "My.Lib.Foo.nullableProp",
                "My.Lib.Foo.__construct",
                "My.Lib.Foo.getValue",
                "My.Lib.Foo.abstractMethod",
                "My.Lib.Foo.refReturnMethod",
                "My.Lib.IFoo",
                "My.Lib.MyTrait",
                "My.Lib.MyTrait.traitMethod",
                "My.Lib.util_func"
        );
        Set<String> actualFqNames = declarations.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(expectedFqNames, actualFqNames, "Declarations in Foo.php mismatch.");
    }

    @Test
    void testGetDeclarationsInFile_NoNamespace() {
        ProjectFile noNsFile = new ProjectFile(testProject.getRoot(), "NoNamespace.php");
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(noNsFile);
        Set<String> expectedFqNames = Set.of(
                "NoNsClass",
                "NoNsClass.property", // Expecting no $ here after SCM fix
                "globalFuncNoNs"
        );
         Set<String> actualFqNames = declarations.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
         assertEquals(expectedFqNames, actualFqNames, "Declarations in NoNamespace.php mismatch.");
    }

    @Test
    void testGetSkeletons_FooClass() {
        ProjectFile fooFile = new ProjectFile(testProject.getRoot(), "Foo.php");
        CodeUnit fooClassCU = CodeUnit.cls(fooFile, "My.Lib", "Foo");
        Optional<String> skeletonOpt = analyzer.getSkeleton(fooClassCU.fqName());
        assertTrue(skeletonOpt.isPresent(), "Skeleton for Foo class should exist.");

        String expectedSkeleton = """
        #[Attribute1]
        class Foo extends BaseFoo implements IFoo, IBar {
          private const MY_CONST = ...;
          public static $staticProp = ...;
          protected $value;
          private ?string $nullableProp;
          #[Attribute2]
          public function __construct(int $v) { ... }
          public function getValue(): int { ... }
          abstract protected function abstractMethod();
          final public static function &refReturnMethod(): array { ... }
        }
        """.stripIndent();
        // Note: The exact rendering of property initializers (= ...) might vary.
        // The test above assumes property declarations without initializers in skeleton for simplicity, or with "...".
        // `const MY_CONST = ...;` is fine as field-like definition node is `const_element` (name + value).
        // `public static $staticProp = ...;` is also fine.
        // `$value` and `$nullableProp` are without initializers.
        // The PhpAnalyzer needs to correctly render property text from `property_element`
        // and const text from `const_element`. The `buildSignatureString` for FIELD_LIKE
        // uses `textSlice(nodeForContent, src).stripLeading().strip()`.
        // This should capture `private const MY_CONST = "hello"` and `public static $staticProp = 123`.
        // Let's update expected:
        expectedSkeleton = """
        #[Attribute1]
        class Foo extends BaseFoo implements IFoo, IBar {
          private const MY_CONST = "hello";
          public static $staticProp = 123;
          protected $value;
          private ?string $nullableProp;
          #[Attribute2]
          public function __construct(int $v) { ... }
          public function getValue(): int { ... }
          abstract protected function abstractMethod();
          final public static function &refReturnMethod(): array { ... }
        }
        """.stripIndent();
        // Note: `abstract protected` order might be `protected abstract` based on `extractModifiers` natural order.
        // The test used `abstract protected`. `extractModifiers` iterates children. The order depends on grammar.
        // Assuming PHP grammar order: visibility, static, abstract/final. So "protected abstract".
        // Test code has `abstract protected`. Let's stick to test file for now.

        assertEquals(expectedSkeleton.trim(), skeletonOpt.get().replace(System.lineSeparator(), "\n").trim(), "Foo class skeleton mismatch.");
    }

    @Test
    void testGetSkeletons_GlobalFunction() {
        ProjectFile fooFile = new ProjectFile(testProject.getRoot(), "Foo.php");
        CodeUnit utilFuncCU = CodeUnit.fn(fooFile, "My.Lib", "util_func");
        Optional<String> skeletonOpt = analyzer.getSkeleton(utilFuncCU.fqName());
        assertTrue(skeletonOpt.isPresent(), "Skeleton for util_func should exist.");
        String expectedSkeleton = "function util_func(): void { ... }"; // Return type was missing.
        assertEquals(expectedSkeleton.trim(), skeletonOpt.get().trim(), "util_func skeleton mismatch.");
    }

    @Test
    void testGetSkeletons_TopLevelConstant() {
        ProjectFile varsFile = new ProjectFile(testProject.getRoot(), "Vars.php");
        // For global constants, CodeUnit is _module_.CONST_NAME
        CodeUnit constCU = CodeUnit.field(varsFile, "", "_module_.TOP_LEVEL_CONST");
        Optional<String> skeletonOpt = analyzer.getSkeleton(constCU.fqName());
        assertTrue(skeletonOpt.isPresent(), "Skeleton for TOP_LEVEL_CONST should exist.");
        // The SCM query for (const_declaration (const_element name value)) @field.definition
        // `buildSignatureString` for FIELD_LIKE uses `textSlice(nodeForContent, src)`
        // where nodeForContent is the `const_element`.
        // This might give "TOP_LEVEL_CONST = 456".
        String expectedSkeleton = "const TOP_LEVEL_CONST = 456;"; // Tree-sitter node for const_element likely contains full "name = value"
                                                                 // PHP field definitions include the semicolon in the node.
                                                                 // `renderFieldDeclaration` (if we had one) or current logic of textSlice
                                                                 // for field.definition should include it.
                                                                 // The base class `buildSignatureString` adds `exportPrefix + fieldDeclText`.
                                                                 // `fieldDeclText = textSlice(nodeForContent, src).stripLeading().strip();`
                                                                 // nodeForContent is `const_element` which is `name: (name_identifier) @field.name EQ value`.
                                                                 // So `textSlice(const_element)` is `TOP_LEVEL_CONST = 456`.
                                                                 // The query captures `const_declaration` as `@field.definition` for top-level.
                                                                 // So `textSlice(const_declaration)` will be `const TOP_LEVEL_CONST = 456;`.
        // If @field.definition is (const_declaration ...), textSlice(const_declaration) is correct.
        // Query: (const_declaration (const_element name: (name_identifier) @field.name ) @field.definition)
        // So nodeForContent is const_declaration.
        assertEquals(expectedSkeleton.trim(), skeletonOpt.get().trim(), "TOP_LEVEL_CONST skeleton mismatch.");
    }

    @Test
    void testGetSkeletons_InterfaceAndTrait() {
        ProjectFile fooFile = new ProjectFile(testProject.getRoot(), "Foo.php");

        CodeUnit interfaceCU = CodeUnit.cls(fooFile, "My.Lib", "IFoo");
        Optional<String> iFooOpt = analyzer.getSkeleton(interfaceCU.fqName());
        assertTrue(iFooOpt.isPresent(), "Skeleton for IFoo interface should exist.");
        assertEquals("interface IFoo { }", iFooOpt.get().trim()); // Adjusted PhpAnalyzer to output this for empty bodies

        CodeUnit traitCU = CodeUnit.cls(fooFile, "My.Lib", "MyTrait");
        Optional<String> traitOpt = analyzer.getSkeleton(traitCU.fqName());
        assertTrue(traitOpt.isPresent(), "Skeleton for MyTrait should exist.");
        String expectedTraitSkeleton = """
        trait MyTrait {
          public function traitMethod() { ... }
        }
        """.stripIndent();
        assertEquals(expectedTraitSkeleton.trim(), traitOpt.get().trim());
    }

    @Test
    void testGetSymbols() {
        ProjectFile fooFile = new ProjectFile(testProject.getRoot(), "Foo.php");
        CodeUnit fooClassCU = CodeUnit.cls(fooFile, "My.Lib", "Foo");
        Set<String> symbols = analyzer.getSymbols(Set.of(fooClassCU));
        Set<String> expectedSymbols = Set.of(
            "Foo", "MY_CONST", "staticProp", "value", "nullableProp",
            "__construct", "getValue", "abstractMethod", "refReturnMethod"
        );
        assertEquals(expectedSymbols, symbols);
    }

    @Test
    void testGetMethodSource() {
        Optional<String> sourceOpt = analyzer.getMethodSource("My.Lib.Foo.getValue");
        assertTrue(sourceOpt.isPresent());
        String expectedSource = """
        public function getValue(): int {
                return $this->value;
            }"""; // Keep original indentation from test file
        // Normalize both by stripping leading/trailing whitespace from each line and rejoining
        java.util.function.Function<String, String> normalize = s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));
        assertEquals(normalize.apply(expectedSource), normalize.apply(sourceOpt.get()));


        Optional<String> constructorSourceOpt = analyzer.getMethodSource("My.Lib.Foo.__construct");
        assertTrue(constructorSourceOpt.isPresent());
        String expectedConstructorSource = """
        #[Attribute2]
        public function __construct(int $v) {
                $this->value = $v;
            }"""; // Keep original indentation
        // Note: Attributes on methods are part of the method_declaration node.
        assertEquals(normalize.apply(expectedConstructorSource), normalize.apply(constructorSourceOpt.get()));
    }

    @Test
    void testGetClassSource() {
         Optional<CodeUnit> fooClassCUOpt = analyzer.getDefinition("My.Lib.Foo");
         assertTrue(fooClassCUOpt.isPresent());
         String classSource = analyzer.getClassSource("My.Lib.Foo");
         String expectedSourceStart = "#[Attribute1]\nclass Foo extends BaseFoo implements IFoo, IBar {";
         assertTrue(classSource.stripIndent().startsWith(expectedSourceStart));
         assertTrue(classSource.stripIndent().endsWith("}")); // Outer class brace
         assertTrue(classSource.contains("private const MY_CONST = \"hello\";"));
         assertTrue(classSource.contains("public function getValue(): int {"));
    }
}
