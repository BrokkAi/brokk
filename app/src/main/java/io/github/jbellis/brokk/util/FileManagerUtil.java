package io.github.jbellis.brokk.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for interacting with the operating system's file manager.
 *
 * Cross-platform "reveal path in file manager" behavior:
 * - Windows: explorer /select,<file> for files; explorer <dir> for directories
 * - macOS: open -R <file> for files; open <dir> for directories
 * - Linux: xdg-open <dir>; fall back to Desktop API if needed
 *
 * Callers should invoke this off the EDT and map exceptions to user-facing notifications.
 */
public final class FileManagerUtil {
    private static final Logger logger = LogManager.getLogger(FileManagerUtil.class);

    private FileManagerUtil() {
        // utility
    }

    /**
     * Opens the operating system's file manager to reveal the given path.
     *
     * @param path The path to reveal in the file manager. Must exist.
     * @throws IOException If the path does not exist or if an I/O error occurs during command execution.
     * @throws UnsupportedOperationException If the operation is not supported on the current operating system.
     */
    public static void revealPath(Path path) throws IOException {
        if (!Files.exists(path)) {
            var errorMessage = "Cannot reveal non-existent path: " + path.toAbsolutePath();
            logger.warn(errorMessage);
            throw new IOException(errorMessage);
        }

        var absolute = path.toAbsolutePath();
        var pathString = absolute.toString();
        ProcessBuilder pb = null;

        if (Environment.isWindows()) {
            var command = new ArrayList<String>();
            command.add("explorer");
            if (Files.isRegularFile(absolute)) {
                command.add("/select," + pathString);
            } else if (Files.isDirectory(absolute)) {
                command.add(pathString);
            } else {
                var msg = "Windows: Unsupported path type for file manager operation: " + pathString;
                logger.warn(msg);
                throw new IOException(msg);
            }
            pb = new ProcessBuilder(command);
            logger.debug("Executing Windows command: {}", String.join(" ", command));
        } else if (Environment.isMacOs()) {
            var command = new ArrayList<String>();
            command.add("open");
            if (Files.isRegularFile(absolute)) {
                command.add("-R");
                command.add(pathString);
            } else if (Files.isDirectory(absolute)) {
                command.add(pathString);
            } else {
                var msg = "macOS: Unsupported path type for file manager operation: " + pathString;
                logger.warn(msg);
                throw new IOException(msg);
            }
            pb = new ProcessBuilder(command);
            logger.debug("Executing macOS command: {}", String.join(" ", command));
        } else if (Environment.isLinux()) {
            // For Linux, there is no standard "reveal" highlight. Best effort:
            // - For files: open the containing directory
            // - For directories: open the directory
            var openTarget = Files.isDirectory(absolute)
                    ? absolute
                    : absolute.getParent() != null ? absolute.getParent() : absolute;

            // Try xdg-open first
            try {
                pb = new ProcessBuilder("xdg-open", openTarget.toString());
                logger.debug("Executing Linux command (xdg-open): xdg-open {}", openTarget);
                var p = pb.start();
                int exitCode = p.waitFor();
                if (exitCode == 0) {
                    logger.info("Successfully opened path with xdg-open: {}", openTarget);
                    return;
                } else {
                    var stderr = readProcessStream(p.getErrorStream());
                    var stdout = readProcessStream(p.getInputStream());
                    logger.warn(
                            "xdg-open command for {} exited with code {}. Stderr: '{}', Stdout: '{}'. Attempting Desktop API fallback.",
                            openTarget,
                            exitCode,
                            stderr,
                            stdout);
                }
            } catch (IOException | InterruptedException e) {
                logger.warn("xdg-open failed or was interrupted for {}. Error: {}. Attempting Desktop API fallback.",
                        openTarget, e.getMessage());
                // fall through to Desktop API
            }

            // Desktop API fallback
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                try {
                    logger.debug("Attempting to open path with Desktop API: {}", openTarget);
                    Desktop.getDesktop().open(openTarget.toFile());
                    logger.info("Successfully opened path with Desktop API: {}", openTarget);
                    return;
                } catch (IOException | UnsupportedOperationException desktopEx) {
                    var msg = "Failed to open path " + openTarget + " using Desktop API. Error: " + desktopEx.getMessage();
                    logger.error(msg, desktopEx);
                    throw new IOException(msg, desktopEx);
                }
            }

            var msg = "Unsupported operation on Linux: cannot open path " + openTarget + " with xdg-open or Desktop API.";
            logger.error(msg);
            throw new IOException(msg);
        } else {
            var errorMessage = "Unsupported operating system for revealing path: " + System.getProperty("os.name");
            logger.error(errorMessage);
            throw new UnsupportedOperationException(errorMessage);
        }

        // For Windows and macOS, start the prepared ProcessBuilder
        try {
            pb.redirectErrorStream(true);
            pb.start();
            logger.info("Successfully launched file manager for path: {}", pathString);
        } catch (IOException e) {
            var errorMessage = "Failed to launch file manager for path "
                    + pathString
                    + ". Command: "
                    + String.join(" ", pb.command())
                    + ". Error: "
                    + e.getMessage();
            logger.error(errorMessage, e);
            throw new IOException(errorMessage, e);
        }
    }

    private static String readProcessStream(java.io.InputStream inputStream) {
        var lines = new ArrayList<String>();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            logger.debug("Error reading process stream: {}", e.getMessage());
        }
        return String.join(System.lineSeparator(), lines).trim();
    }
}
