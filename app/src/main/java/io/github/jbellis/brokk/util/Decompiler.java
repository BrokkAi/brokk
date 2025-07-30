package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

public class Decompiler {
    private static final Logger logger = LogManager.getLogger(Decompiler.class);

    /**
     * Extracts Maven coordinates from a {@code pom.properties} file found under {@code META-INF/maven/}.
     */
    private static Optional<String> getMavenCoordinatesFromPomProperties(Path jarPath) {
        try (var zip = new ZipFile(jarPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (!entry.isDirectory() && name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                    var props = new Properties();
                    try (var in = zip.getInputStream(entry)) {
                        props.load(in);
                    }
                    var groupId = props.getProperty("groupId");
                    var artifactId = props.getProperty("artifactId");
                    var version = props.getProperty("version");
                    if (groupId != null && artifactId != null && version != null) {
                        var coords = String.format("%s:%s:%s", groupId, artifactId, version);
                        logger.debug("Determined Maven coordinates for {} as {} from pom.properties", jarPath, coords);
                        return Optional.of(coords);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read pom.properties from {}: {}", jarPath, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Performs the decompilation of the selected JAR file.
     * This method assumes jarPath is a valid JAR file.
     * @param io The Chrome instance for UI feedback
     * @param jarPath Path to the JAR file to decompile.
     * @param runner TaskRunner to run the decompilation task on
     */
    public static void decompileJar(Chrome io, Path jarPath, ContextManager.TaskRunner runner) {
        decompileJar(io, jarPath, runner, null);
    }

    /**
     * Overload that accepts an optional EDT callback once decompilation finishes.
     */
    public static void decompileJar(Chrome io,
                                    Path jarPath,
                                    ContextManager.TaskRunner runner,
                                    @Nullable Runnable onComplete) {
        String jarName = jarPath.getFileName().toString();
        Path originalProjectRoot = io.getContextManager().getRoot();
        Path brokkDir = originalProjectRoot.resolve(".brokk");
        Path depsDir = brokkDir.resolve("dependencies");
        Path outputDir = depsDir.resolve(jarName.replaceAll("\\.jar$", ""));

        io.systemOutput("Importing " + jarName + "...");

        runner.submit("Importing " + jarName, () -> {
            try {
                // Attempt to download sources if we can determine Maven coordinates
                var coordsOpt = getMavenCoordinatesFromPomProperties(jarPath);

                Optional<Path> sourcesJarPathOpt = Optional.empty();
                if (coordsOpt.isPresent()) {
                    var coords = coordsOpt.get();
                    io.systemOutput("Detected Maven coordinates: " + coords + ". Attempting to download sources...");
                    var fetcher = new MavenArtifactFetcher();
                    sourcesJarPathOpt = fetcher.fetch(coords, "sources");
                }

                // If not downloaded, check for a local sibling
                if (sourcesJarPathOpt.isEmpty()) {
                    if (coordsOpt.isEmpty()) {
                        logger.info("No pom.properties found in {}. Checking for local *-sources.jar before decompiling.", jarPath.getFileName());
                    }
                    Path localSources = jarPath.resolveSibling(jarName.replace(".jar", "-sources.jar"));
                    if (Files.exists(localSources)) {
                        sourcesJarPathOpt = Optional.of(localSources);
                    }
                }

                // Prepare output directory, asking for overwrite if necessary
                if (!prepareOutputDirectory(io, outputDir)) {
                    logger.debug("User chose not to overwrite existing dependency directory {}", outputDir);
                    return null;
                }

                if (sourcesJarPathOpt.isPresent()) {
                    Path sourcesJarPath = sourcesJarPathOpt.get();
                    io.systemOutput("Found sources JAR. Unpacking " + sourcesJarPath.getFileName() + "...");
                    extractJarToTemp(sourcesJarPath, outputDir);
                    io.systemOutput("Sources unpacked. Reopen project to incorporate the new source files.");
                } else {
                    if (coordsOpt.isPresent()) {
                        logger.info("Could not find sources for {}. Falling back to decompilation.", coordsOpt.get());
                    }
                    decompile(io, jarPath, outputDir);
                }

                if (onComplete != null) {
                    SwingUtilities.invokeLater(onComplete);
                }
            } catch (Exception e) {
                io.toolError("Error during import process: " + e.getMessage());
                logger.error("Error processing JAR {}", jarPath, e);
            }
            return null;
        });
    }

    private static boolean prepareOutputDirectory(Chrome io, Path outputDir) throws Exception {
        Files.createDirectories(outputDir.getParent());

        if (Files.exists(outputDir)) {
            var proceed = new AtomicBoolean(false);
            var latch = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                try {
                    int choice = JOptionPane.showConfirmDialog(
                            io.getFrame(),
                            """
                            This dependency appears to exist already.
                            Output directory: %s

                            Delete output directory and import again?
                            (Choosing 'No' will leave the existing files unchanged.)
                            """.formatted(outputDir.toString()),
                            "Dependency exists",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE
                    );
                    proceed.set(choice == JOptionPane.YES_OPTION);
                } finally {
                    latch.countDown();
                }
            });
            latch.await();

            if (proceed.get()) {
                logger.debug("Removing old dependency contents at {}", outputDir);
                deleteDirectoryRecursive(outputDir);
            } else {
                return false;
            }
        }

        Files.createDirectories(outputDir);
        return true;
    }

    private static void decompile(Chrome io, Path jarPath, Path outputDir) throws Exception {
        io.systemOutput("Decompiling " + jarPath.getFileName() + "...");
        logger.debug("Starting decompilation in background thread for {}", jarPath);
        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("fernflower-extracted-");
            extractJarToTemp(jarPath, tempDir);

            Map<String, Object> options = Map.of("hes", "1", "hdc", "1", "dgs", "1", "ren", "1");
            ConsoleDecompiler decompiler = new ConsoleDecompiler(
                    outputDir.toFile(),
                    options,
                    new org.jetbrains.java.decompiler.main.extern.IFernflowerLogger() {
                        @Override
                        public void writeMessage(String message, Severity severity) {
                            switch (severity) {
                                case ERROR -> logger.error("Fernflower: {}", message);
                                case WARN  -> logger.warn("Fernflower: {}", message);
                                case INFO  -> logger.info("Fernflower: {}", message);
                                case TRACE -> logger.trace("Fernflower: {}", message);
                                default    -> logger.debug("Fernflower: {}", message);
                            }
                        }

                        @Override
                        public void writeMessage(String message, Severity severity, Throwable t) {
                            switch (severity) {
                                case ERROR -> logger.error("Fernflower: {}", message, t);
                                case WARN  -> logger.warn("Fernflower: {}", message, t);
                                case INFO  -> logger.info("Fernflower: {}", message, t);
                                case TRACE -> logger.trace("Fernflower: {}", message, t);
                                default   -> logger.debug("Fernflower: {}", message, t);
                            }
                        }
                    }
            );

            decompiler.addSource(tempDir.toFile());
            decompiler.decompileContext();

            io.systemOutput("Decompilation completed. Reopen project to incorporate the new source files.");
        } finally {
            if (tempDir != null) {
                try {
                    deleteDirectoryRecursive(tempDir);
                } catch (IOException e) {
                    logger.error("Failed to delete temporary directory: {}", tempDir, e);
                }
            }
        }
    }

    public static void extractJarToTemp(Path jarPath, Path targetDir) throws IOException {
        // Ensure target directory exists and is a directory
        if (!Files.isDirectory(targetDir)) {
            Files.createDirectories(targetDir); // Create if not exists
        }
        // Use try-with-resources to ensure the ZipFile is closed
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            var entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                // Resolve and normalize, then ensure it's within the target directory
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // --- Zip Slip Protection ---
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry is trying to escape the target directory: " + entry.getName());
                }
                // --- End Zip Slip Protection ---

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // Ensure parent directories exist for the file
                    Files.createDirectories(entryPath.getParent());
                    // Use try-with-resources for the input stream
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    public static void deleteDirectoryRecursive(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    logger.warn("Failed to delete file: {} ({})", file, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                if (exc != null) {
                    logger.warn("Error visiting directory contents for deletion: {} ({})", dir, exc.getMessage());
                    throw exc; // Propagate error
                }
                try {
                    Files.delete(dir);
                } catch (IOException e) {
                    logger.warn("Failed to delete directory: {} ({})", dir, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("Failed to access file for deletion: {} ({})", file, exc.getMessage());
                // Decide if failure should stop the process
                return FileVisitResult.CONTINUE; // Continue deletion attempt
            }
        });
    }
}
