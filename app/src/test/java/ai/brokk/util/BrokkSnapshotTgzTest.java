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
