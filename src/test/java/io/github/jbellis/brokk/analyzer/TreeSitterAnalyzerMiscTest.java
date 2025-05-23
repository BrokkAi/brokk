package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.git.IGitRepo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class TreeSitterAnalyzerMiscTest {

    // Copied from TreeSitterAnalyzerTest.java for test project setup
    final static class TestProject implements IProject {
        private final Path root;
        private final Language language;

        TestProject(Path root, Language language) {
            this.root = root;
            this.language = language;
        }

        @Override
        public Language getAnalyzerLanguage() {
            return language;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public IGitRepo getRepo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(p -> new ProjectFile(root, root.relativize(p)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                System.err.printf("ERROR (TestProject.getAllFiles): walk failed on %s: %s%n",
                                  root, e.getMessage());
                e.printStackTrace(System.err);
                return Collections.emptySet();
            }
        }
    }

    private static TestProject createTestProject(String subDir, Language lang) {
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    private static TestProject jsTestProject;
    private static JavascriptAnalyzer jsAnalyzer;
    private static ProjectFile helloJsFile;
    private static ProjectFile helloJsxFile;
    private static ProjectFile varsJsFile;


    @BeforeAll
    static void setup() {
        jsTestProject = createTestProject("testcode-js", Language.JAVASCRIPT);
        jsAnalyzer = new JavascriptAnalyzer(jsTestProject);
        assertFalse(jsAnalyzer.isEmpty(), "Analyzer should have processed JS/JSX files");

        helloJsFile = new ProjectFile(jsTestProject.getRoot(), "Hello.js");
        helloJsxFile = new ProjectFile(jsTestProject.getRoot(), "Hello.jsx");
        varsJsFile = new ProjectFile(jsTestProject.getRoot(), "Vars.js");

    }

    @Test
    void testIsCpg() {
        assertFalse(jsAnalyzer.isCpg(), "TreeSitterAnalyzer instances should not be CPGs.");
    }

    @Test
    void testGetSkeletonHeader() {
        // Test case 1: Class in JSX
        Optional<String> jsxClassHeader = jsAnalyzer.getSkeletonHeader("JsxClass");
        assertTrue(jsxClassHeader.isPresent(), "Skeleton header for JsxClass should be defined.");
        assertEquals("export class JsxClass {", jsxClassHeader.get().trim());

        // Test case 2: Arrow function component in JSX
        Optional<String> jsxArrowFnHeader = jsAnalyzer.getSkeletonHeader("JsxArrowFnComponent");
        assertTrue(jsxArrowFnHeader.isPresent(), "Skeleton header for JsxArrowFnComponent should be defined.");
        assertEquals("export JsxArrowFnComponent({ name }): JSX.Element => ...", jsxArrowFnHeader.get().trim());
        
        // Test case 3: Regular function in JS
        Optional<String> utilFuncHeader = jsAnalyzer.getSkeletonHeader("util"); // From Hello.js
        assertTrue(utilFuncHeader.isPresent(), "Skeleton header for util function should be defined.");
        assertEquals("export function util() ...", utilFuncHeader.get().trim());

        // Test case 4: Non-existent FQ name
        Optional<String> nonExistentHeader = jsAnalyzer.getSkeletonHeader("NonExistentSymbol");
        assertTrue(nonExistentHeader.isEmpty(), "Skeleton header for a non-existent symbol should be empty.");
    }

    @Test
    void testGetMembersInClass() {
        // Test case 1: JsxClass in Hello.jsx
        List<CodeUnit> jsxClassMembers = jsAnalyzer.getMembersInClass("JsxClass");
        assertFalse(jsxClassMembers.isEmpty(), "JsxClass should have members.");
        CodeUnit renderMethod = CodeUnit.fn(helloJsxFile, "", "JsxClass.render");
        assertTrue(jsxClassMembers.contains(renderMethod), "JsxClass members should include render method.");
        assertEquals(1, jsxClassMembers.size(), "JsxClass should have 1 member (render).");

        // Test case 2: Hello class in Hello.js
        List<CodeUnit> helloClassMembers = jsAnalyzer.getMembersInClass("Hello");
        assertFalse(helloClassMembers.isEmpty(), "Hello class should have members.");
        CodeUnit greetMethod = CodeUnit.fn(helloJsFile, "", "Hello.greet");
        assertTrue(helloClassMembers.contains(greetMethod), "Hello class members should include greet method.");
        assertEquals(1, helloClassMembers.size(), "Hello class should have 1 member (greet).");

        // Test case 3: Non-class FQ name (e.g., a function)
        List<CodeUnit> functionMembers = jsAnalyzer.getMembersInClass("util"); // 'util' is a function
        assertTrue(functionMembers.isEmpty(), "A function should not have members in this context.");

        // Test case 4: Non-existent FQ name
        List<CodeUnit> nonExistentMembers = jsAnalyzer.getMembersInClass("NonExistentClass");
        assertTrue(nonExistentMembers.isEmpty(), "A non-existent class should have no members.");
    }

    @Test
    void testGetFileFor() {
        // Test case 1: Function in Hello.js
        Optional<ProjectFile> utilFile = jsAnalyzer.getFileFor("util");
        assertTrue(utilFile.isPresent(), "File for 'util' function should be found.");
        assertEquals(helloJsFile, utilFile.get());

        // Test case 2: Class in Hello.jsx
        Optional<ProjectFile> jsxClassFile = jsAnalyzer.getFileFor("JsxClass");
        assertTrue(jsxClassFile.isPresent(), "File for 'JsxClass' should be found.");
        assertEquals(helloJsxFile, jsxClassFile.get());

        // Test case 3: Method in Hello.jsx (JsxClass.render)
        Optional<ProjectFile> renderMethodFile = jsAnalyzer.getFileFor("JsxClass.render");
        assertTrue(renderMethodFile.isPresent(), "File for 'JsxClass.render' method should be found.");
        assertEquals(helloJsxFile, renderMethodFile.get());
        
        // Test case 4: Top-level variable in Vars.js
        Optional<ProjectFile> topConstJsFile = jsAnalyzer.getFileFor("_module_.TOP_CONST_JS");
        assertTrue(topConstJsFile.isPresent(), "File for '_module_.TOP_CONST_JS' should be found.");
        assertEquals(varsJsFile, topConstJsFile.get());

        // Test case 5: Non-existent FQ name
        Optional<ProjectFile> nonExistentFile = jsAnalyzer.getFileFor("NonExistentSymbol");
        assertTrue(nonExistentFile.isEmpty(), "File for a non-existent symbol should not be found.");
    }

    @Test
    void testGetDefinition() {
        // Test case 1: Arrow function component in Hello.jsx
        Optional<CodeUnit> jsxArrowFnDef = jsAnalyzer.getDefinition("JsxArrowFnComponent");
        assertTrue(jsxArrowFnDef.isPresent(), "Definition for 'JsxArrowFnComponent' should be found.");
        assertEquals("JsxArrowFnComponent", jsxArrowFnDef.get().fqName());
        assertEquals(helloJsxFile, jsxArrowFnDef.get().source());

        // Test case 2: Method in Hello.js
        Optional<CodeUnit> greetMethodDef = jsAnalyzer.getDefinition("Hello.greet");
        assertTrue(greetMethodDef.isPresent(), "Definition for 'Hello.greet' method should be found.");
        assertEquals("Hello.greet", greetMethodDef.get().fqName());
        assertEquals(helloJsFile, greetMethodDef.get().source());
        
        // Test case 3: Top-level variable in Vars.js
        Optional<CodeUnit> topConstJsDef = jsAnalyzer.getDefinition("_module_.TOP_CONST_JS");
        assertTrue(topConstJsDef.isPresent(), "Definition for '_module_.TOP_CONST_JS' should be found.");
        assertEquals("_module_.TOP_CONST_JS", topConstJsDef.get().fqName());
        assertEquals(varsJsFile, topConstJsDef.get().source());

        // Test case 4: Non-existent FQ name
        Optional<CodeUnit> nonExistentDef = jsAnalyzer.getDefinition("NonExistentSymbol");
        assertTrue(nonExistentDef.isEmpty(), "Definition for a non-existent symbol should not be found.");
    }

    @Test
    void testSearchDefinitions() {
        // Test case 1: Search for "Jsx"
        List<CodeUnit> jsxSymbols = jsAnalyzer.searchDefinitions("Jsx");
        assertFalse(jsxSymbols.isEmpty(), "Should find symbols containing 'Jsx'.");
        Set<String> jsxFqNames = jsxSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(jsxFqNames.contains("JsxClass"));
        assertTrue(jsxFqNames.contains("JsxClass.render")); // Member of JsxClass
        assertTrue(jsxFqNames.contains("JsxArrowFnComponent"));
        assertTrue(jsxFqNames.contains("LocalJsxArrowFn")); // Non-exported local arrow function
        assertTrue(jsxFqNames.contains("PlainJsxFunc"));    // Non-exported plain function
        assertEquals(5, jsxSymbols.size(), "Expected 5 symbols containing 'Jsx'.");

        // Test case 2: Search for "Hello" (matches class name)
        List<CodeUnit> helloSymbols = jsAnalyzer.searchDefinitions("Hello");
        assertFalse(helloSymbols.isEmpty(), "Should find symbols containing 'Hello'.");
        Set<String> helloFqNames = helloSymbols.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(helloFqNames.contains("Hello"), "Should find 'Hello' class.");
        assertTrue(helloFqNames.contains("Hello.greet"), "Should find 'Hello.greet' method.");
        // JsxArrowFnComponent fqName does not contain "Hello", so it shouldn't be matched by fqName search.
        assertEquals(2, helloSymbols.size(), "Expected 2 symbols containing 'Hello' in their FQ names.");

        // Test case 3: Search for ".render" (matches method name part)
        List<CodeUnit> renderSymbols = jsAnalyzer.searchDefinitions(".render");
        assertFalse(renderSymbols.isEmpty(), "Should find symbols containing '.render'.");
        assertTrue(renderSymbols.stream().anyMatch(cu -> "JsxClass.render".equals(cu.fqName())), "Should find 'JsxClass.render'.");
        assertEquals(1, renderSymbols.size(), "Expected 1 symbol matching '.render'.");
        
        // Test case 4: Search for "TOP_CONST" (matches variable name part)
        List<CodeUnit> constSymbols = jsAnalyzer.searchDefinitions("TOP_CONST");
        assertFalse(constSymbols.isEmpty(), "Should find symbols containing 'TOP_CONST'.");
        assertTrue(constSymbols.stream().anyMatch(cu -> "_module_.TOP_CONST_JS".equals(cu.fqName())), "Should find '_module_.TOP_CONST_JS'.");
        assertEquals(1, constSymbols.size(), "Expected 1 symbol matching 'TOP_CONST'.");

        // Test case 5: Non-existent pattern
        List<CodeUnit> nonExistentSymbols = jsAnalyzer.searchDefinitions("NonExistentPatternXYZ");
        assertTrue(nonExistentSymbols.isEmpty(), "Searching for a non-existent pattern should return an empty list.");

        // Test case 6: Null pattern
        List<CodeUnit> nullPatternSymbols = jsAnalyzer.searchDefinitions(null);
        assertTrue(nullPatternSymbols.isEmpty(), "Searching with a null pattern should return an empty list.");

        // Test case 7: Empty pattern
        List<CodeUnit> emptyPatternSymbols = jsAnalyzer.searchDefinitions("");
        assertTrue(emptyPatternSymbols.isEmpty(), "Searching with an empty pattern should return an empty list.");
    }

    @Test
    void testGetClassSource_Js() {
        // Test case 1: Valid class from Hello.js
        String helloClassSource = jsAnalyzer.getClassSource("Hello");
        String expectedHelloClassSource = """
        export class Hello {
            greet() { console.log("hi"); }
        }""";
        assertEquals(expectedHelloClassSource.stripIndent().trim(), helloClassSource.stripIndent().trim());

        // Test case 2: Valid class from Hello.jsx
        String jsxClassSource = jsAnalyzer.getClassSource("JsxClass");
        String expectedJsxClassSource = """
        export class JsxClass {
            render() {
                return <div className="class-jsx">Hello from JSX Class</div>;
            }
        }""";
        assertEquals(expectedJsxClassSource.stripIndent().trim(), jsxClassSource.stripIndent().trim());

        // Test case 3: Non-existent class
        assertThrows(SymbolNotFoundException.class, () -> jsAnalyzer.getClassSource("NonExistentClass"),
                     "Requesting source for a non-existent class should throw SymbolNotFoundException.");

        // Test case 4: Existing symbol that is a function, not a class
        assertThrows(SymbolNotFoundException.class, () -> jsAnalyzer.getClassSource("util"),
                     "Requesting class source for a function symbol should throw SymbolNotFoundException.");

        // Test case 5: Existing symbol that is a field, not a class
        assertThrows(SymbolNotFoundException.class, () -> jsAnalyzer.getClassSource("_module_.TOP_CONST_JS"),
                     "Requesting class source for a field symbol should throw SymbolNotFoundException.");
    }

    @Test
    void testGetMethodSource_Js() {
        // Test case 1: Method in Hello.js
        Optional<String> greetMethodSourceOpt = jsAnalyzer.getMethodSource("Hello.greet");
        assertTrue(greetMethodSourceOpt.isPresent(), "Source for 'Hello.greet' should be found.");
        String expectedGreetMethodSource = """
        greet() { console.log("hi"); }""";
        assertEquals(expectedGreetMethodSource.stripIndent().trim(), greetMethodSourceOpt.get().stripIndent().trim());

        // Test case 2: Method in Hello.jsx
        Optional<String> renderMethodSourceOpt = jsAnalyzer.getMethodSource("JsxClass.render");
        assertTrue(renderMethodSourceOpt.isPresent(), "Source for 'JsxClass.render' should be found.");
        String expectedRenderMethodSource = """
        render() {
                return <div className="class-jsx">Hello from JSX Class</div>;
            }"""; // Note: Indentation within the method body is preserved.
        assertEquals(expectedRenderMethodSource.stripIndent().trim(), renderMethodSourceOpt.get().stripIndent().trim());

        // Test case 3: Exported function in Hello.js
        Optional<String> utilFuncSourceOpt = jsAnalyzer.getMethodSource("util");
        assertTrue(utilFuncSourceOpt.isPresent(), "Source for 'util' function should be found.");
        String expectedUtilFuncSource = """
        export function util() { return 42; }""";
        assertEquals(expectedUtilFuncSource.stripIndent().trim(), utilFuncSourceOpt.get().stripIndent().trim());

        // Test case 4: Exported arrow function in Hello.jsx
        Optional<String> jsxArrowFnSourceOpt = jsAnalyzer.getMethodSource("JsxArrowFnComponent");
        assertTrue(jsxArrowFnSourceOpt.isPresent(), "Source for 'JsxArrowFnComponent' should be found.");
        // Note: The source for an arrow function assigned to a const/let is just the arrow function part.
        String expectedJsxArrowFnSource = """
        ({ name }) => {
            return (
                <section>
                    <p>Hello, {name} from JSX Arrow Function!</p>
                </section>
            );
        }""";
        assertEquals(expectedJsxArrowFnSource.stripIndent().trim(), jsxArrowFnSourceOpt.get().stripIndent().trim());

        // Test case 5: Local (non-exported) arrow function in Hello.jsx
        Optional<String> localJsxArrowFnSourceOpt = jsAnalyzer.getMethodSource("LocalJsxArrowFn");
        assertTrue(localJsxArrowFnSourceOpt.isPresent(), "Source for 'LocalJsxArrowFn' should be found.");
        String expectedLocalJsxArrowFnSource = """
        () => <button>Click Me</button>""";
        assertEquals(expectedLocalJsxArrowFnSource.stripIndent().trim(), localJsxArrowFnSourceOpt.get().stripIndent().trim());

        // Test case 6: Local (non-exported) plain function in Hello.jsx
        Optional<String> plainJsxFuncSourceOpt = jsAnalyzer.getMethodSource("PlainJsxFunc");
        assertTrue(plainJsxFuncSourceOpt.isPresent(), "Source for 'PlainJsxFunc' should be found.");
        String expectedPlainJsxFuncSource = """
        function PlainJsxFunc() {
            return <article>Some article content</article>;
        }""";
        assertEquals(expectedPlainJsxFuncSource.stripIndent().trim(), plainJsxFuncSourceOpt.get().stripIndent().trim());

        // Test case 7: Non-existent method
        Optional<String> nonExistentMethodSourceOpt = jsAnalyzer.getMethodSource("NonExistent.method");
        assertTrue(nonExistentMethodSourceOpt.isEmpty(), "Requesting source for a non-existent method should return Option.empty().");

        // Test case 8: Existing symbol that is a class, not a function
        Optional<String> classAsMethodSourceOpt = jsAnalyzer.getMethodSource("Hello");
        assertTrue(classAsMethodSourceOpt.isEmpty(), "Requesting method source for a class symbol should return Option.empty().");
        
        // Test case 9: Existing symbol that is a field, not a function
        Optional<String> fieldAsMethodSourceOpt = jsAnalyzer.getMethodSource("_module_.TOP_CONST_JS");
        assertTrue(fieldAsMethodSourceOpt.isEmpty(), "Requesting method source for a field symbol should return Option.empty().");
    }
}
