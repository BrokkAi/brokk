package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ReexportTest {

    private static TestProject project;
    private static TypescriptAnalyzer analyzer;

    @BeforeAll
    static void setUp(@TempDir Path tempDir) throws IOException {
        Path testResourceRoot =
                Path.of("src/test/resources/testcode-ts/reexports").toAbsolutePath();
        project = new TestProject(testResourceRoot, Languages.TYPESCRIPT);
        analyzer = new TypescriptAnalyzer(project);
    }

    @Test
    void testWildcardReexport() throws Exception {
        // Create temp file for wildcard test
        Path tempFile = Files.createTempFile("test", ".ts");
        Files.writeString(tempFile, "export * from './users';");
        var file = new ProjectFile(tempFile.getParent(), tempFile.getFileName().toString());

        var reexports = analyzer.getReexports(file);

        assertEquals(1, reexports.size());
        var reexport = reexports.get(0);
        assertTrue(reexport.exportAll(), "Should be a wildcard export");
        assertEquals("./users", reexport.source());
        assertTrue(reexport.symbols().isEmpty(), "Wildcard exports have no specific symbols");
        assertNull(reexport.namespace(), "Wildcard export should have no namespace");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testNamedReexport() throws Exception {
        Path tempFile = Files.createTempFile("test", ".ts");
        Files.writeString(tempFile, "export { User, UserService } from './users';");
        var file = new ProjectFile(tempFile.getParent(), tempFile.getFileName().toString());

        var reexports = analyzer.getReexports(file);

        assertEquals(1, reexports.size());
        var reexport = reexports.get(0);
        assertFalse(reexport.exportAll(), "Should not be a wildcard export");
        assertEquals("./users", reexport.source());
        assertEquals(2, reexport.symbols().size(), "Should have 2 symbols");
        assertTrue(reexport.symbols().contains("User"), "Should contain User");
        assertTrue(reexport.symbols().contains("UserService"), "Should contain UserService");
        assertTrue(reexport.renamed().isEmpty(), "No renaming should occur");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testRenamedReexport() throws Exception {
        Path tempFile = Files.createTempFile("test", ".ts");
        Files.writeString(tempFile, "export { User as PublicUser } from './internal';");
        var file = new ProjectFile(tempFile.getParent(), tempFile.getFileName().toString());

        var reexports = analyzer.getReexports(file);

        assertEquals(1, reexports.size());
        var reexport = reexports.get(0);
        assertFalse(reexport.exportAll());
        assertEquals("./internal", reexport.source());
        assertEquals(1, reexport.symbols().size(), "Should have 1 symbol");
        assertTrue(reexport.symbols().contains("User"), "Should contain original name User");
        assertEquals(Map.of("User", "PublicUser"), reexport.renamed(), "Should have renamed mapping");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testNamespaceReexport() throws Exception {
        Path tempFile = Files.createTempFile("test", ".ts");
        Files.writeString(tempFile, "export * as Types from './types';");
        var file = new ProjectFile(tempFile.getParent(), tempFile.getFileName().toString());

        var reexports = analyzer.getReexports(file);

        assertEquals(1, reexports.size());
        var reexport = reexports.get(0);
        assertFalse(reexport.exportAll(), "Namespace export is not same as wildcard");
        assertEquals("./types", reexport.source());
        assertEquals("Types", reexport.namespace(), "Should have namespace name");
        assertTrue(reexport.symbols().isEmpty(), "Namespace exports have no specific symbols");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testMultipleReexports() throws Exception {
        Path tempFile = Files.createTempFile("test", ".ts");
        Files.writeString(
                tempFile,
                """
            export * from './users';
            export { Post, Comment } from './posts';
            export * as Types from './types';
            """);
        var file = new ProjectFile(tempFile.getParent(), tempFile.getFileName().toString());

        var reexports = analyzer.getReexports(file);
        assertEquals(3, reexports.size(), "Should capture 3 re-export statements");

        // Verify wildcard export
        var wildcardReexport = reexports.stream()
                .filter(r -> r.exportAll())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No wildcard export found"));
        assertEquals("./users", wildcardReexport.source());

        // Verify named export
        var namedReexport = reexports.stream()
                .filter(r -> !r.symbols().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No named export found"));
        assertEquals("./posts", namedReexport.source());
        assertTrue(namedReexport.symbols().contains("Post"));
        assertTrue(namedReexport.symbols().contains("Comment"));

        // Verify namespace export
        var namespaceReexport = reexports.stream()
                .filter(r -> r.namespace() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No namespace export found"));
        assertEquals("./types", namespaceReexport.source());
        assertEquals("Types", namespaceReexport.namespace());

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testMixedRenamedReexport() throws Exception {
        Path tempFile = Files.createTempFile("test", ".ts");
        Files.writeString(tempFile, "export { User as PublicUser, Role } from './auth';");
        var file = new ProjectFile(tempFile.getParent(), tempFile.getFileName().toString());

        var reexports = analyzer.getReexports(file);

        assertEquals(1, reexports.size());
        var reexport = reexports.get(0);
        assertEquals("./auth", reexport.source());
        assertEquals(2, reexport.symbols().size());
        assertTrue(reexport.symbols().contains("User"), "Should have User");
        assertTrue(reexport.symbols().contains("Role"), "Should have Role");
        assertEquals(1, reexport.renamed().size(), "Only User is renamed");
        assertEquals("PublicUser", reexport.renamed().get("User"));
        assertFalse(reexport.renamed().containsKey("Role"), "Role is not renamed");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testNoReexports() throws Exception {
        Path tempFile = Files.createTempFile("test", ".ts");
        Files.writeString(
                tempFile,
                """
            export class User {}
            export function foo() {}
            import { Bar } from './bar';
            """);
        var file = new ProjectFile(tempFile.getParent(), tempFile.getFileName().toString());

        var reexports = analyzer.getReexports(file);
        assertTrue(reexports.isEmpty(), "File has no re-exports, only regular exports and imports");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testReexportFactoryMethods() {
        // Test wildcard factory
        var wildcard = ReexportInfo.wildcard("./users");
        assertTrue(wildcard.exportAll());
        assertEquals("./users", wildcard.source());
        assertTrue(wildcard.symbols().isEmpty());
        assertTrue(wildcard.renamed().isEmpty());
        assertNull(wildcard.namespace());

        // Test named factory
        var named = ReexportInfo.named("./models", java.util.List.of("User", "Role"));
        assertFalse(named.exportAll());
        assertEquals("./models", named.source());
        assertEquals(2, named.symbols().size());
        assertTrue(named.symbols().contains("User"));
        assertTrue(named.symbols().contains("Role"));
        assertTrue(named.renamed().isEmpty());

        // Test namespace factory
        var namespace = ReexportInfo.namespace("./types", "Types");
        assertFalse(namespace.exportAll());
        assertEquals("./types", namespace.source());
        assertEquals("Types", namespace.namespace());
        assertTrue(namespace.symbols().isEmpty());

        // Test renamed factory
        var renamed = ReexportInfo.renamed("./internal", java.util.List.of("User"), Map.of("User", "PublicUser"));
        assertEquals("./internal", renamed.source());
        assertEquals(1, renamed.symbols().size());
        assertEquals("PublicUser", renamed.renamed().get("User"));
    }
}
