package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.IProject;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.testutil.TestService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Windows-only regression test for Issue #1768: "FUF is broken on Windows".
 *
 * Reproduces CRLF-related line offset miscalculation by:
 *  - Copying existing test resources to a temp directory, enforcing CRLF line endings.
 *  - Running FuzzyUsageFinder on those files.
 *  - Asserting expected usages are found (which fail prior to the fix on Windows).
 *
 * This test is skipped on non-Windows platforms.
 */
public class FuzzyUsageFinderWindowsRegressionTest {

    @Test
    public void windowsCrlfNewlineHandling_regressionTest() throws Exception {
        // Gate this test to run only on Windows
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        assumeTrue(isWindows, "Windows-only regression test for CRLF newline handling");

        // Prepare a CRLF-enforced copy of existing test fixtures to ensure deterministic line endings
        Path srcDir = Path.of("./src/test/resources", "testcode-java")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.exists(srcDir), "Missing test resources dir: " + srcDir);
        assertTrue(Files.isDirectory(srcDir), "Not a directory: " + srcDir);

        Path tempRoot =
                Files.createTempDirectory("fuf-windows-crlf-").toAbsolutePath().normalize();

        // Copy files and enforce CRLF endings
        try (var paths = Files.list(srcDir)) {
            for (Path p : paths.toList()) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String content = Files.readString(p, StandardCharsets.UTF_8);
                // Normalize to LF first, then enforce CRLF to avoid doubling CR characters.
                String crlf = content.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
                Path dest = tempRoot.resolve(p.getFileName());
                Files.writeString(
                        dest,
                        crlf,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        }

        // Build a minimal IProject for the CRLF-enforced temp directory
        IProject project = new IProject() {
            @Override
            public Path getRoot() {
                return tempRoot;
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                try (var stream = Files.list(tempRoot)) {
                    return stream.filter(Files::isRegularFile)
                            .map(file -> new ProjectFile(tempRoot, tempRoot.relativize(file)))
                            .collect(Collectors.toSet());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to list files in temp project root: " + tempRoot, e);
                }
            }

            @Override
            public void close() {
                // no-op for this test
            }
        };

        // Use the Java analyzer on the temp CRLF project
        TreeSitterAnalyzer analyzer = new JavaAnalyzer(project);
        FuzzyUsageFinder finder = new FuzzyUsageFinder(project, analyzer, new TestService(project), null);

        // Repro: lookup usages of A.method2
        String symbol = "A.method2";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        assertNotNull(hits, "Expected non-null usage hits for " + symbol);
        assertFalse(hits.isEmpty(), "Expected usages for " + symbol + " with CRLF files on Windows");

        // Validate we find the expected files (regression: these would be missing prior to fix on Windows)
        Set<String> files = hits.stream()
                .map(hit -> hit.file().absPath().getFileName().toString())
                .collect(Collectors.toSet());

        assertTrue(files.contains("B.java"), "Expected a usage in B.java; actual: " + files);
        assertTrue(files.contains("AnonymousUsage.java"), "Expected a usage in AnonymousUsage.java; actual: " + files);

        // Sanity: hits should have valid, positive line numbers and non-empty snippets
        hits.forEach(uh -> {
            assertTrue(uh.line() > 0, "Expected positive 1-based line number, got: " + uh.line());
            assertNotNull(uh.snippet(), "Expected non-null snippet");
            assertFalse(uh.snippet().isEmpty(), "Expected non-empty snippet");
        });
    }
}
