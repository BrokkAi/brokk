package io.github.jbellis.brokk.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class FileUtil {
    private static final Logger logger = LogManager.getLogger(FileUtil.class);

    private FileUtil() {
        /* utility class – no instances */
    }

    /**
     * Deletes {@code path} and everything beneath it. Does **not** follow
     * symlinks; logs but ignores individual delete failures.
     */
    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        logger.warn("Failed to delete {}", p, e);
                    }
                });
        }
    }
}
