package io.github.jbellis.brokk.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A utility class to handle Lombok integration for static analysis.
 * It provides methods to detect if a project uses Lombok and to run
 * the Delombok tool programmatically.
 */
public final class LombokAnalysisUtils {

    private static final Logger logger = LoggerFactory.getLogger(LombokAnalysisUtils.class);

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private LombokAnalysisUtils() {
    }

    /**
     * Determines if a project at a given root directory uses Lombok.
     * It does this by scanning for any .java file that imports a Lombok package.
     *
     * @param projectRoot The root directory of the source code.
     * @return true if a Lombok import is found, false otherwise.
     */
    public static boolean projectUsesLombok(Path projectRoot) {
        if (!Files.isDirectory(projectRoot)) {
            logger.warn("Provided path is not a directory: {}", projectRoot);
            return false;
        }

        logger.debug("Scanning for Lombok usage in: {}", projectRoot);
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .anyMatch(LombokAnalysisUtils::fileContainsLombokImport);
        } catch (IOException e) {
            logger.error("Error walking file tree while checking for Lombok usage.", e);
            return false;
        }
    }

    /**
     * Helper method to check if a single file contains a Lombok import.
     * This method is efficient as it stops reading the file as soon as a match is found.
     *
     * @param javaFile The path to the .java file.
     * @return true if the file imports from `lombok.`, false otherwise.
     */
    private static boolean fileContainsLombokImport(Path javaFile) {
        try (Stream<String> lines = Files.lines(javaFile)) {
            // anyMatch is a short-circuiting operation, which is efficient.
            return lines.anyMatch(line -> line.trim().startsWith("import lombok."));
        } catch (IOException e) {
            // This might happen with file system issues or unreadable files.
            logger.error("Could not read file: {}", javaFile, e);
            return false;
        }
    }

    /**
     * Runs the Delombok process by executing the lombok.jar as a separate process.
     * This is the most robust method as it avoids classloader issues.
     *
     * @param sourceDir The directory containing the original source code.
     * @param outputDir The directory where the delomboked code will be written.
     * @return true if successful, false otherwise.
     */
    public static boolean runDelombok(Path sourceDir, Path outputDir) {
        Optional<Path> lombokJarPathOpt = findLombokJarPath();
        if (lombokJarPathOpt.isEmpty()) {
            logger.error("Could not find lombok.jar on the classpath. Cannot run Delombok.");
            return false;
        }

        Path lombokJarPath = lombokJarPathOpt.get();
        logger.info("Starting Delombok process using jar: Source {} -> Output {}", sourceDir, outputDir);

        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-jar",
                lombokJarPath.toString(),
                "delombok",
                sourceDir.toAbsolutePath().toString(),
                "-d",
                outputDir.toAbsolutePath().toString()
        );

        try {
            Process process = pb.start();

            // Capture and log output for debugging
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
                reader.lines().forEach(line -> logger.debug("[Delombok] {}", line));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), UTF_8))) {
                reader.lines().forEach(line -> logger.error("[Delombok] {}", line));
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("Delombok process completed successfully.");
                return true;
            } else {
                logger.error("Delombok process finished with exit code: {}", exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("An exception occurred while running Delombok process.", e);
            Thread.currentThread().interrupt(); // Preserve interrupted status
            return false;
        }
    }

    /**
     * Finds the path to the lombok.jar file on the application's classpath.
     *
     * @return An Optional containing the path to lombok.jar, or empty if not found.
     */
    private static Optional<Path> findLombokJarPath() {
        String classpath = System.getProperty("java.class.path");
        String separator = File.pathSeparator;

        return Stream.of(classpath.split(separator))
                .map(Paths::get)
                .filter(p -> p.getFileName().toString().startsWith("lombok-") && p.toString().endsWith(".jar"))
                .findFirst();
    }
}