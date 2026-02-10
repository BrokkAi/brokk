package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test to verify that JavaScript arrow functions are optimally captured via query patterns
 * (Phase 2 optimization) rather than runtime AST traversal.
 */
public class JavascriptArrowFunctionTest {

    private static final Logger logger = LoggerFactory.getLogger(JavascriptArrowFunctionTest.class);

    @Test
    void testTopLevelArrowFunctions(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("arrows.js");
        Files.writeString(
                testFile,
                """
            const myFunc = (x) => x * 2;
            const asyncFunc = async () => { return 42; };
            let anotherFunc = (a, b) => a + b;
            """);

        var project = new TestProject(tempDir, Languages.JAVASCRIPT);
        var analyzer = new JavascriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "Declarations found: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var functions = declarations.stream().filter(CodeUnit::isFunction).toList();

        assertEquals(3, functions.size(), "Should capture 3 arrow functions");

        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("myFunc")), "Should capture myFunc");
        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("asyncFunc")), "Should capture asyncFunc");
        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("anotherFunc")), "Should capture anotherFunc");
    }

    @Test
    void testExportedArrowFunctions(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("exported.js");
        Files.writeString(
                testFile,
                """
            export const handler = (req, res) => {
                res.send('hello');
            };

            export const middleware = async (req, res, next) => {
                next();
            };
            """);

        var project = new TestProject(tempDir, Languages.JAVASCRIPT);
        var analyzer = new JavascriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "Declarations found: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var functions = declarations.stream().filter(CodeUnit::isFunction).toList();

        assertEquals(2, functions.size(), "Should capture 2 exported arrow functions");
        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("handler")), "Should capture handler");
        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("middleware")), "Should capture middleware");
    }

    @Test
    void testMixedFunctionTypes(@TempDir Path tempDir) throws IOException {
        var testFile = tempDir.resolve("mixed.js");
        Files.writeString(
                testFile,
                """
            // Regular function
            function regularFunc() { }

            // Arrow function
            const arrowFunc = () => { };

            // Class with method
            class MyClass {
                methodFunc() { }
            }
            """);

        var project = new TestProject(tempDir, Languages.JAVASCRIPT);
        var analyzer = new JavascriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "All declarations: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var functions = declarations.stream().filter(CodeUnit::isFunction).toList();

        // Should have: regularFunc, arrowFunc, methodFunc
        assertEquals(3, functions.size(), "Should capture 3 functions total");

        assertTrue(
                functions.stream().anyMatch(f -> f.fqName().contains("regularFunc")),
                "Should capture regular function");
        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("arrowFunc")), "Should capture arrow function");
        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("methodFunc")), "Should capture class method");

        // Verify class is also captured
        var classes = declarations.stream().filter(CodeUnit::isClass).toList();
        assertEquals(1, classes.size(), "Should capture MyClass");
    }

    @Test
    void testReactPatterns(@TempDir Path tempDir) throws IOException {
        // Common React patterns using arrow functions
        var testFile = tempDir.resolve("component.js");
        Files.writeString(
                testFile,
                """
            const MyComponent = () => {
                return <div>Hello</div>;
            };

            const useCustomHook = () => {
                const [state, setState] = useState(0);
                return [state, setState];
            };

            export const ComponentWithProps = ({ name, age }) => {
                return <div>{name} is {age}</div>;
            };
            """);

        var project = new TestProject(tempDir, Languages.JAVASCRIPT);
        var analyzer = new JavascriptAnalyzer(project);
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));

        var declarations = analyzer.getDeclarations(projectFile);
        logger.info(
                "React components found: {}",
                declarations.stream().map(CodeUnit::fqName).toList());

        var functions = declarations.stream().filter(CodeUnit::isFunction).toList();

        assertEquals(3, functions.size(), "Should capture all 3 React arrow functions");
        assertTrue(functions.stream().anyMatch(f -> f.fqName().contains("MyComponent")), "Should capture MyComponent");
        assertTrue(
                functions.stream().anyMatch(f -> f.fqName().contains("useCustomHook")), "Should capture useCustomHook");
        assertTrue(
                functions.stream().anyMatch(f -> f.fqName().contains("ComponentWithProps")),
                "Should capture ComponentWithProps");
    }
}
