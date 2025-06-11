package io.github.jbellis.brokk.util;


import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Environment {
    private static final Logger logger = LogManager.getLogger(Environment.class);
    public static final Environment instance = new Environment();

    private TrayIcon brokkTrayIcon = null;

    private Environment() {
    }

    /**
     * Runs a shell command using the appropriate shell for the current OS,
     * returning combined stdout and stderr. The command is executed in the directory
     * specified by {@code root}. Output lines are passed to the consumer as they are generated.
     * @param command The command to execute.
     * @param root The working directory for the command.
     * @param outputConsumer A consumer that accepts output lines (from stdout or stderr) as they are produced.
     * @throws SubprocessException if the command fails to start, times out, or returns a non-zero exit code.
     * @throws InterruptedException if the thread is interrupted.
     */
    public String runShellCommand(String command, Path root, java.util.function.Consumer<String> outputConsumer)
    throws SubprocessException, InterruptedException
    {
        logger.debug("Running `{}` in `{}`", command, root);
        String shell = isWindows() ? "cmd.exe" : "/bin/sh";
        String[] shellCommand = isWindows()
                                ? new String[]{"cmd.exe", "/c", command}
                                : new String[]{"/bin/sh", "-c", command};

        ProcessBuilder pb = createProcessBuilder(root, shellCommand);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new StartupException("unable to start %s in %s for command: `%s` (%s)".formatted(shell, root, command, e.getMessage()), "");
        }

        // start draining stdout/stderr immediately to avoid pipe-buffer deadlock
        CompletableFuture<String> stdoutFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getInputStream(), outputConsumer));
        CompletableFuture<String> stderrFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream(), outputConsumer));

        String combinedOutput;
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly(); // ensure the process is terminated
                // Collect any output produced before timeout
                String stdout = stdoutFuture.join();
                String stderr = stderrFuture.join();
                combinedOutput = formatOutput(stdout, stderr);
                throw new TimeoutException("process '%s' did not complete within 120 seconds".formatted(command), combinedOutput);
            }
        } catch (InterruptedException ie) {
            process.destroyForcibly();
            // Collect any output produced before interruption
            String stdout = stdoutFuture.join(); // Try to get output, might be empty
            String stderr = stderrFuture.join();
            combinedOutput = formatOutput(stdout, stderr);
            logger.warn("Process '{}' interrupted. Output so far: {}", command, combinedOutput);
            throw ie; // Propagate InterruptedException
        }

        // process has exited – collect whatever is left
        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();
        combinedOutput = formatOutput(stdout, stderr);
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            throw new FailureException("process '%s' signalled error code %d".formatted(command, exitCode), combinedOutput);
        }

        return combinedOutput;
    }

    private static String readStream(java.io.InputStream in, java.util.function.Consumer<String> outputConsumer) {
        var lines = new java.util.ArrayList<String>();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputConsumer.accept(line);
                lines.add(line);
            }
        } catch (IOException e) {
            logger.error("Error reading stream", e);
            // If an error occurs during streaming, consumer has processed what it could.
            // The returned string will contain lines accumulated so far.
        }
        return String.join("\n", lines);
    }

    private static final String ANSI_ESCAPE_PATTERN = "\\x1B(?:\\[[;\\d]*[ -/]*[@-~]|\\]\\d+;[^\\x07]*\\x07)";

    private static String formatOutput(String stdout, String stderr) {
        stdout = stdout.trim().replaceAll(ANSI_ESCAPE_PATTERN, "");
        stderr = stderr.trim().replaceAll(ANSI_ESCAPE_PATTERN, "");

        if (stdout.isEmpty() && stderr.isEmpty()) {
            return "";
        }
        if (stdout.isEmpty()) {
            return stderr;
        }
        if (stderr.isEmpty()) {
            return stdout;
        }
        return "stdout:\n" + stdout + "\n\nstderr:\n" + stderr;
    }

    private static ProcessBuilder createProcessBuilder(Path root, String... command) {
        var pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        // Redirect input from /dev/null (or NUL on Windows) so interactive prompts fail fast
        if (isWindows()) {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
        } else {
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        }
        // Remove environment variables that might interfere with non-interactive operation
        pb.environment().remove("EDITOR");
        pb.environment().remove("VISUAL");
        pb.environment().put("TERM", "dumb");
        return pb;
    }

    /**
     * Base exception for subprocess errors.
     */
    public static abstract class SubprocessException extends IOException {
        private final String output;

        public SubprocessException(String message, String output) {
            super(message);
            this.output = output == null ? "" : output;
        }

        public String getOutput() {
            return output;
        }
    }

    /**
     * Exception thrown when a subprocess fails to start.
     */
    public static class StartupException extends SubprocessException {
        public StartupException(String message, String output) {
            super(message, output);
        }
    }

    /**
     * Exception thrown when a subprocess times out.
     */
    public static class TimeoutException extends SubprocessException {
        public TimeoutException(String message, String output) {
            super(message, output);
        }
    }

    /**
     * Exception thrown when a subprocess returns a non-zero exit code.
     */
    public static class FailureException extends SubprocessException {
        public FailureException(String message, String output) {
            super(message, output);
        }
    }

    /**
     * Determines if the current operating system is Windows.
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win");
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("mac");
    }

    /**
     * Sends a desktop notification asynchronously.
     *
     * @param title    The title of the notification.
     * @param message  The message body of the notification.
     */
    public void sendNotificationAsync(String message) {
        CompletableFuture.runAsync(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (isSystemTrayNotificationSupported()) {
                    sendSystemTrayNotification(message);
                } else if (os.contains("linux")) {
                    sendLinuxNotification(message);
                } else {
                    logger.info("Desktop notifications not supported on this platform ({})", os);
                }
            } catch (Exception e) {
                logger.warn("Failed to send desktop notification", e);
            }
        });
    }

    private boolean isSystemTrayNotificationSupported() {
        return !isMacOs() && SystemTray.isSupported();
    }

    private void sendLinuxNotification(String message) throws IOException {
        runCommandFireAndForget("notify-send", "--category", "Brokk", message);
    }

    private synchronized void sendSystemTrayNotification(String message) {
        assert SystemTray.isSupported(); // caller is responsible for checking

        if (this.brokkTrayIcon == null) {
            // Check if SystemTray is supported before attempting to get it.
            // isSystemTrayNotificationSupported() already checks SystemTray.isSupported()
            // but this method could theoretically be called directly.
            SystemTray tray = SystemTray.getSystemTray();
            var iconUrl = Chrome.class.getResource(Brokk.ICON_RESOURCE);
            if (iconUrl == null) {
                logger.error("Brokk icon resource not found, cannot create TrayIcon.");
                return;
            }
            var image = new ImageIcon(iconUrl).getImage();
            TrayIcon newTrayIcon = new TrayIcon(image, "Brokk");
            newTrayIcon.setImageAutoSize(true);

            try {
                tray.add(newTrayIcon);
                this.brokkTrayIcon = newTrayIcon; // Assign only if add succeeds
            } catch (AWTException e) {
                logger.warn("Could not add TrayIcon to SystemTray: {}", e.getMessage());
                // brokkTrayIcon remains null, message won't be displayed via tray this time.
                // Subsequent calls will attempt to add it again.
                return; // Do not proceed to displayMessage if icon wasn't added
            }
        }

        this.brokkTrayIcon.displayMessage("Brokk", message, TrayIcon.MessageType.INFO);
    }

    private void runCommandFireAndForget(String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        logger.debug("running command: {}", String.join(" ", command));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    /**
     * Opens the specified URL in the default web browser.
     * Handles common errors like browser unavailability.
     *
     * @param url      The URL to open.
     * @param ancestor The parent window for displaying error dialogs, can be null.
     */
    public static void openInBrowser(String url, Window ancestor) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                logger.warn("Desktop.Action.BROWSE not supported, cannot open URL: {}", url);
                JOptionPane.showMessageDialog(
                        ancestor,
                        "Sorry, unable to open browser automatically. Desktop API not supported.\nPlease visit: " + url,
                        "Browser Unsupported",
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }
            Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (UnsupportedOperationException ex) {
            logger.error("Browser not supported on this platform (e.g., WSL): {}", url, ex);
            JOptionPane.showMessageDialog(
                    ancestor,
                    "Sorry, unable to open browser automatically. This is a known problem on WSL.\nPlease visit: " + url,                    "Browser Unsupported",
                    JOptionPane.WARNING_MESSAGE
            );
        } catch (Exception ex) {
            logger.error("Failed to open URL: {}", url, ex);
            JOptionPane.showMessageDialog(
                    ancestor,
                    "Failed to open the browser. Please visit:\n" + url,
                    "Browser Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
}
