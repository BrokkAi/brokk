package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Compatibility test ensuring that lambda naming produced by JdtClient (LSP) is compatible with
 * JavaTreeSitterAnalyzer's expectations for resolving and obtaining source summaries.
 *
 * <p>Workflow:
 *
 * <ul>
 *   <li>Use JdtClient.getAnonymousName(Location) to generate an LSP-derived lambda fullname.
 *   <li>Verify that JavaTreeSitterAnalyzer.getDefinition(fullname) succeeds and returns correct source.
 * </ul>
 */
public class JavaLambdaNamingCompatibilityTest {

    @Nullable
    private static JavaTreeSitterAnalyzer tsAnalyzer;

    @Nullable
    private static TestProject testProject;

    @Nullable
    private static JdtClient jdtClient;

    @BeforeAll
    public static void setup() throws IOException, InterruptedException {
        final var testPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-java' not found.");

        testProject = new TestProject(testPath, Languages.JAVA);
        tsAnalyzer = new JavaTreeSitterAnalyzer(testProject);
        jdtClient = new JdtClient(testProject);
    }

    @AfterAll
    public static void teardown() {
        if (jdtClient != null) {
            try {
                jdtClient.close();
            } catch (Exception ignored) {
            }
        }
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    @Disabled("LSP Does not index fields for search so we cannot fetch this in this way")
    public void lspNameResolves_InterfaceDEFAULTLambda() {
        // Interface.DEFAULT contains: root -> { };
        assertNotNull(tsAnalyzer, "TreeSitter analyzer should be initialized");
        assertNotNull(jdtClient, "JDT client should be initialized");

        final var maybeFile = tsAnalyzer.getFileFor("Interface");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class Interface");
        final var file = maybeFile.get();

        // Known 0-based location of the lambda in the test source (as used by existing tests)
        final int line = 5; // 0-based
        final int col = 24; // 0-based
        final String expectedLspFullName = "Interface.Interface$anon$5:24";
        final String expectedSource = "root -> { }";

        final String lspFullName = deriveAnonymousNameFromLsp(file, line, col);
        assertEquals(
                expectedLspFullName,
                lspFullName,
                "LSP-derived lambda fullname mismatch; indicates naming incompatibility");

        // Now verify TreeSitter can resolve this name and return the source
        final var maybeCu = tsAnalyzer.getDefinition(lspFullName);
        assertTrue(maybeCu.isPresent(), "TreeSitter could not resolve LSP-derived lambda name: " + lspFullName);
        final var cu = maybeCu.get();

        final var srcOpt = tsAnalyzer.getSourceForCodeUnit(cu, false);
        assertTrue(srcOpt.isPresent(), "Should be able to fetch source for lambda");
        assertEquals(expectedSource, srcOpt.get(), "Lambda source is incorrect");
    }

    @Test
    public void lspNameResolves_AnonymousUsage_ifPresentLambda() {
        // AnonymousUsage.NestedClass.getSomething contains: ifPresent(s -> map.put("foo", "test"))
        assertNotNull(tsAnalyzer, "TreeSitter analyzer should be initialized");
        assertNotNull(jdtClient, "JDT client should be initialized");

        final var maybeFile = tsAnalyzer.getFileFor("AnonymousUsage");
        assertTrue(maybeFile.isPresent(), "Should resolve file for class AnonymousUsage");
        final var file = maybeFile.get();

        // Known 0-based location of the lambda in the test source (as used by existing tests)
        final int line = 15; // 0-based
        final int col = 37; // 0-based
        final String expectedLspFullName = "AnonymousUsage.NestedClass.getSomething$anon$15:37";
        final String expectedSource = "s -> map.put(\"foo\", \"test\")";

        final String lspFullName = deriveAnonymousNameFromLsp(file, line, col);
        assertEquals(
                expectedLspFullName,
                lspFullName,
                "LSP-derived lambda fullname mismatch; indicates naming incompatibility");

        // Now verify TreeSitter can resolve this name and return the source
        final var maybeCu = tsAnalyzer.getDefinition(lspFullName);
        assertTrue(maybeCu.isPresent(), "TreeSitter could not resolve LSP-derived lambda name: " + lspFullName);
        final var cu = maybeCu.get();

        final var srcOpt = tsAnalyzer.getSourceForCodeUnit(cu, false);
        assertTrue(srcOpt.isPresent(), "Should be able to fetch source for lambda");
        assertEquals(expectedSource, srcOpt.get(), "Lambda source is incorrect");
    }

    /**
     * Uses JDT LSP (via JdtClient) to derive the anonymous/lambda fullname from a file and (line, col) position. This
     * is the authoritative LSP naming to be validated against TreeSitter expectations.
     */
    private String deriveAnonymousNameFromLsp(ProjectFile file, int line, int col) {
        final var uri = file.absPath().toUri();
        // Provide a 1-character range to ensure containment checks succeed; LSP uses 0-based positions
        final var start = new Position(line, col);
        final var end = new Position(line, col + 1);
        final var range = new Range(start, end);
        final var location = new Location(uri.toString(), range);

        final var lspNameOpt = jdtClient.getAnonymousName(location);
        assertTrue(
                lspNameOpt.isPresent(),
                "JDT LSP did not produce an anonymous/lambda name for location: " + summarizeLocation(uri, line, col));
        return lspNameOpt.get();
    }

    private static String summarizeLocation(URI uri, int line, int col) {
        return uri + "@" + line + ":" + col;
    }
}
