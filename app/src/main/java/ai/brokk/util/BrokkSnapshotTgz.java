package ai.brokk.util;

import ai.brokk.concurrent.AtomicWrites;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jspecify.annotations.NullMarked;

/**
 * Utility to download and extract the Brokk master-snapshot release.
 */
@NullMarked
public final class BrokkSnapshotTgz {
    private static final Logger logger = LogManager.getLogger(BrokkSnapshotTgz.class);

    public static final String SNAPSHOT_URL =
            "https://github.com/BrokkAi/brokk-releases/releases/download/master-snapshot/brokk-0.0.0-master-snapshot.tgz";

    private static final String BUNDLE_PREFIX = "package/jdeploy-bundle/";
    private static final Pattern JAR_PATTERN = Pattern.compile("brokk-.*\\.jar");

    private BrokkSnapshotTgz() {}

    /**
     * Downloads the snapshot tgz to a temp file and extracts the embedded Brokk jar.
     *
     * @param tempDir        Directory to store the downloaded tgz (temporarily)
     * @param destinationDir Directory to extract the jar into
     * @return Path to the extracted jar
     * @throws IOException If download or extraction fails
     */
    @Blocking
    public static Path downloadAndExtractJar(Path tempDir, Path destinationDir) throws IOException {
        Path tgzFile = tempDir.resolve("brokk-snapshot.tgz");
        downloadSnapshot(tgzFile);
        return extractJarFromTgz(tgzFile, destinationDir);
    }

    private static void downloadSnapshot(Path targetFile) throws IOException {
        logger.info("Downloading Brokk snapshot from {}", SNAPSHOT_URL);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(SNAPSHOT_URL)).GET().build();

        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to download snapshot: HTTP " + response.statusCode());
            }

            try (InputStream is = response.body()) {
                AtomicWrites.save(targetFile, out -> is.transferTo(out));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    /** Internal helper for testing extraction logic without network. */
    static Path extractJarFromTgz(Path tgzFile, Path destinationDir) throws IOException {
        logger.info("Extracting jar from {}", tgzFile);
        Files.createDirectories(destinationDir);

        try (InputStream fis = Files.newInputStream(tgzFile);
                BufferedInputStream bis = new BufferedInputStream(fis);
                GzipCompressorInputStream gzis = new GzipCompressorInputStream(bis);
                TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

            TarArchiveEntry entry;
            while ((entry = tais.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                // Strip leading ./ if present
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }

                if (name.startsWith(BUNDLE_PREFIX)) {
                    String filename = name.substring(BUNDLE_PREFIX.length());
                    if (JAR_PATTERN.matcher(filename).matches()) {
                        Path targetPath = destinationDir.resolve(filename);
                        logger.info("Extracting {} to {}", name, targetPath);
                        AtomicWrites.save(targetPath, out -> tais.transferTo(out));
                        return targetPath;
                    }
                }
            }
        }

        throw new IOException("No matching jar found in snapshot tgz at " + BUNDLE_PREFIX + "brokk-*.jar");
    }
}
