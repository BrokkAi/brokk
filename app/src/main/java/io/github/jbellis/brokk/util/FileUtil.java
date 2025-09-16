package io.github.jbellis.brokk.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FileUtil {
    private static final Logger logger = LogManager.getLogger(FileUtil.class);

    private FileUtil() {
        /* utility class – no instances */
    }

    /** Represents the result of reading a file with BOM stripping. */
    public static class FileContent {
        private final String content;
        private final byte[] bytes;

        private FileContent(String content, byte[] bytes) {
            this.content = content;
            this.bytes = bytes;
        }

        public String getContent() {
            return content;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }

    /**
     * Reads a file as a UTF-8 string, automatically stripping UTF-8 BOM if present. Returns both the string content and
     * the BOM-stripped byte array to ensure TreeSitter analyzers can use identical content for parsing and source
     * extraction.
     *
     * @param path the file path to read
     * @return FileContent containing both string and byte representations with UTF-8 BOM stripped if it was present
     * @throws IOException if an I/O error occurs reading from the file
     */
    public static FileContent readFileWithBomStripping(Path path) throws IOException {
        byte[] fileBytes = Files.readAllBytes(path);

        // Strip UTF-8 BOM if present (EF BB BF)
        if (fileBytes.length >= 3
                && (fileBytes[0] & 0xFF) == 0xEF
                && (fileBytes[1] & 0xFF) == 0xBB
                && (fileBytes[2] & 0xFF) == 0xBF) {
            byte[] bytesWithoutBom = new byte[fileBytes.length - 3];
            System.arraycopy(fileBytes, 3, bytesWithoutBom, 0, fileBytes.length - 3);
            fileBytes = bytesWithoutBom;
            logger.trace("Stripped UTF-8 BOM from file: {}", path);
        }

        String content = new String(fileBytes, StandardCharsets.UTF_8);
        return new FileContent(content, fileBytes);
    }

    /**
     * Reads a file as a UTF-8 string, automatically stripping UTF-8 BOM if present. This ensures consistent file
     * reading across the application, especially important for TreeSitter analyzers where byte offsets must align with
     * character positions.
     *
     * @param path the file path to read
     * @return the file content as a string with UTF-8 BOM stripped if it was present
     * @throws IOException if an I/O error occurs reading from the file
     */
    public static String readStringWithBomStripping(Path path) throws IOException {
        return readFileWithBomStripping(path).getContent();
    }

    /**
     * Deletes {@code path} and everything beneath it. Does **not** follow symlinks; logs but ignores individual delete
     * failures.
     */
    public static boolean deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return false;
        }

        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    logger.warn("Failed to delete {}", p, e);
                }
            });
            return !Files.exists(path);
        } catch (IOException e) {
            logger.error("Failed to walk or initiate deletion for directory: {}", path, e);
            return false;
        }
    }
}
