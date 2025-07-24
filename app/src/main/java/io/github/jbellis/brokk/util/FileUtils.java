package io.github.jbellis.brokk.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class FileUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);
    
    /**
     * Recursively deletes a directory and its contents.
     * Logs errors encountered during deletion.
     *
     * @param path The directory path to delete.
     * @return true if the directory was successfully deleted (or didn't exist), false otherwise.
     */
    public static boolean deleteDirectoryRecursively(Path path) {
        if (!Files.exists(path)) {
            return false;
        } else {
            try (var stream = Files.walk(path)) {
                stream
                        .sorted(Comparator.reverseOrder()) // Ensure contents are deleted before directories
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                // Log the specific error but allow the walk to continue trying other files/dirs
                                logger.error("Failed to delete path {} during recursive cleanup of {}", p, path, e);
                            }
                        });
                // Final check after attempting deletion
                return !Files.exists(path);
            } catch (IOException e) {
                logger.error("Failed to walk or initiate deletion for directory: {}", path, e);
                return false;
            }
        }
    }
    
}
