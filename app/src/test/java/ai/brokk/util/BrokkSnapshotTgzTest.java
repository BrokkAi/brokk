package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrokkSnapshotTgzTest {

    @TempDir
    Path tempDir;

    @Test
    void extractJarFromTgz_findsCorrectEntry() throws IOException {
        Path tgzFile = tempDir.resolve("test.tgz");
        Path destDir = tempDir.resolve("out");

        createTestTgz(
                tgzFile,
                "other/file.txt",
                "content",
                "package/jdeploy-bundle/ignored.txt",
                "content",
                "package/jdeploy-bundle/brokk-1.2.3.jar",
                "jar-content");

        // We can't easily test downloadAndExtractJar because of the hardcoded URL,
        // but we can expose or test the extraction logic if we refactor slightly
        // or just test the extraction from a file directly.
        // For the sake of this task, I'll use a package-private extraction method or reflection-free test.

        // Testing the logic via a simulated file (extractJarFromTgz is private in the impl,
        // but for testing we assume we can verify the behavior).

        Path extracted = BrokkSnapshotTgz.extractJarFromTgz(tgzFile, destDir);

        assertEquals("brokk-1.2.3.jar", extracted.getFileName().toString());
        assertEquals("jar-content", Files.readString(extracted));
        assertTrue(Files.exists(extracted));
    }

    @Test
    void extractJarFromTgz_failsIfNotFound() throws IOException {
        Path tgzFile = tempDir.resolve("test-fail.tgz");
        Path destDir = tempDir.resolve("out-fail");

        createTestTgz(tgzFile, "wrong/path/brokk-1.2.3.jar", "content");

        assertThrows(IOException.class, () -> BrokkSnapshotTgz.extractJarFromTgz(tgzFile, destDir));
    }

    @Test
    void extractJarFromTgz_selectionConstraintsAndOrder() throws IOException {
        Path tgzFile = tempDir.resolve("selection.tgz");
        Path destDir = tempDir.resolve("selection-out");

        // Prepare a tar with multiple candidates to test constraints:
        // 1. package/jdeploy-bundle/brokk-111.jar -> MATCH
        // 2. package/jdeploy-bundle/not-brokk.jar -> FAIL (regex)
        // 3. other/brokk-222.jar                  -> FAIL (prefix)
        // 4. package/jdeploy-bundle/brokk-333.jar -> MATCH (but should be ignored if 1 is found first)
        createTestTgz(
                tgzFile,
                "package/jdeploy-bundle/brokk-111.jar",
                "correct-jar",
                "package/jdeploy-bundle/not-brokk.jar",
                "wrong-name",
                "other/brokk-222.jar",
                "wrong-path",
                "package/jdeploy-bundle/brokk-333.jar",
                "duplicate-match");

        Path extracted = BrokkSnapshotTgz.extractJarFromTgz(tgzFile, destDir);

        // Assert only the FIRST valid match is returned
        assertEquals("brokk-111.jar", extracted.getFileName().toString());
        assertEquals("correct-jar", Files.readString(extracted));

        // Ensure others weren't extracted
        assertTrue(Files.notExists(destDir.resolve("not-brokk.jar")));
        assertTrue(Files.notExists(destDir.resolve("brokk-222.jar")));
        assertTrue(Files.notExists(destDir.resolve("brokk-333.jar")));
    }

    private void createTestTgz(Path path, String... entries) throws IOException {
        try (OutputStream fos = Files.newOutputStream(path);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(bos);
                TarArchiveOutputStream tos = new TarArchiveOutputStream(gzos)) {

            for (int i = 0; i < entries.length; i += 2) {
                String name = entries[i];
                byte[] content = entries[i + 1].getBytes(StandardCharsets.UTF_8);

                TarArchiveEntry entry = new TarArchiveEntry(name);
                entry.setSize(content.length);
                tos.putArchiveEntry(entry);
                tos.write(content);
                tos.closeArchiveEntry();
            }
        }
    }
}
