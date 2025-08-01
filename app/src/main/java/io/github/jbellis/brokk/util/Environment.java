package io.github.jbellis.brokk.util;


import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.gui.Chrome;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.util.function.BiFunction;
import java.util.function.Consumer;

public class Environment {
    private static final Logger logger = LogManager.getLogger(Environment.class);
    public static final Environment instance = new Environment();

    @FunctionalInterface
    public interface ShellCommandRunner {
        String run(Consumer<String> outputConsumer) throws SubprocessException, InterruptedException;
    }

    // Default factory creates the real runner. Tests can replace this.
    public static final BiFunction<String, Path, ShellCommandRunner> DEFAULT_SHELL_COMMAND_RUNNER_FACTORY =
            (cmd, projectRoot) -> (outputConsumer) -> runShellCommandInternal(cmd, projectRoot, outputConsumer);

    public static BiFunction<String, Path, ShellCommandRunner> shellCommandRunnerFactory =
            DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;


    @Nullable private TrayIcon brokkTrayIcon = null;

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
    public String runShellCommand(String command, Path root, Consumer<String> outputConsumer)
            throws SubprocessException, InterruptedException
    {
        return shellCommandRunnerFactory.apply(command, root).run(outputConsumer);
    }

    /**
     * Runs a shell command with optional sandbox.
     * When {@code sandbox} is {@code false} this is identical to
     * {@link #runShellCommand(String, Path, Consumer)}.
     */
    public String runShellCommand(String command,
                                  Path root,
                                  boolean sandbox,
                                  Consumer<String> outputConsumer)
            throws SubprocessException, InterruptedException
    {
        if (!sandbox) {
            return runShellCommand(command, root, outputConsumer);
        }
        return runShellCommandInternal(command, root, true, outputConsumer);
    }

    private static String runShellCommandInternal(String command, Path root, Consumer<String> outputConsumer)
            throws SubprocessException, InterruptedException {
        logger.debug("Running internal `{}` in `{}`", command, root);
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
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            logger.warn("Process '{}' interrupted.", command);
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

    /**
     * Internal helper that supports running the command in a sandbox when requested.
     */
    private static String runShellCommandInternal(String command,
                                                  Path root,
                                                  boolean sandbox,
                                                  Consumer<String> outputConsumer)
            throws SubprocessException, InterruptedException 
    {
        logger.debug("Running internal `{}` in `{}` (sandbox={})", command, root, sandbox);

        String[] shellCommand;
        if (sandbox) {
            if (isWindows()) {
                throw new UnsupportedOperationException("sandboxing is not supported on Windows");
            }

            if (isMacOs()) {
                // Build a seatbelt policy: read-only everywhere, write only inside root
                String absPath = root.toAbsolutePath().toString();
                String writeRule;
                try {
                    String realPath = root.toRealPath().toString();
                    if (realPath.equals(absPath)) {
                        writeRule = "(allow file-write* (subpath \"" + escapeForSeatbelt(absPath) + "\"))";
                    } else {
                        writeRule = "(allow file-write* (subpath \"" + escapeForSeatbelt(absPath) + "\") " +
                                "(subpath \"" + escapeForSeatbelt(realPath) + "\"))";
                    }
                } catch (IOException e) {
                    // Fallback to only the absolute path if realPath resolution fails
                    writeRule = "(allow file-write* (subpath \"" + escapeForSeatbelt(absPath) + "\"))";
                }

                var fullPolicy = READ_ONLY_SEATBELT_POLICY + "\n" + writeRule;

                // Write policy to a temporary file; avoids newline-parsing issues
                Path policyFile;
                try {
                    policyFile = Files.createTempFile("brokk-seatbelt-", ".sb");
                    Files.writeString(policyFile, fullPolicy, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new StartupException("unable to create seatbelt policy file: " + e.getMessage(), "");
                }
                policyFile.toFile().deleteOnExit();

                shellCommand = new String[]{
                        "sandbox-exec",
                        "-f",
                        policyFile.toString(),
                        "--",
                        "/bin/sh",
                        "-c",
                        command
                };
            } else {
                // TODO
                throw new UnsupportedOperationException();
            }
        } else {
            shellCommand = isWindows()
                    ? new String[]{"cmd.exe", "/c", command}
                    : new String[]{"/bin/sh", "-c", command};
        }

        ProcessBuilder pb = createProcessBuilder(root, shellCommand);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            var shell = isWindows() ? "cmd.exe" : "/bin/sh";
            throw new StartupException("unable to start %s in %s for command: `%s` (%s)".formatted(shell, root, command, e.getMessage()), "");
        }

        CompletableFuture<String> stdoutFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getInputStream(), outputConsumer));
        CompletableFuture<String> stderrFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream(), outputConsumer));

        String combinedOutput;
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                String stdout = stdoutFuture.join();
                String stderr = stderrFuture.join();
                combinedOutput = formatOutput(stdout, stderr);
                throw new TimeoutException("process '%s' did not complete within 120 seconds".formatted(command), combinedOutput);
            }
        } catch (InterruptedException ie) {
            process.destroyForcibly();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            logger.warn("Process '{}' interrupted.", command);
            throw ie;
        }

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
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
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

    /**
     * Seatbelt policy that grants read access everywhere and write access only
     * to explicitly whitelisted roots. Networking remains blocked.
     */
    private static final String READ_ONLY_SEATBELT_POLICY = """
        (version 1)

        (deny default)

        ; allow read-only file operations
        (allow file-read*)

        ; allow process creation
        (allow process-exec)
        (allow process-fork)
        (allow signal (target self))

        ; allow writing to /dev/null
        (allow file-write-data
          (require-all
            (path "/dev/null")
            (vnode-type CHARACTER-DEVICE)))

        ; sysctl whitelist required by the JVM and many Unix tools
        (allow sysctl-read
          (sysctl-name "hw.activecpu")
          (sysctl-name "hw.busfrequency_compat")
          (sysctl-name "hw.byteorder")
          (sysctl-name "hw.cacheconfig")
          (sysctl-name "hw.cachelinesize_compat")
          (sysctl-name "hw.cpufamily")
          (sysctl-name "hw.cpufrequency_compat")
          (sysctl-name "hw.cputype")
          (sysctl-name "hw.l1dcachesize_compat")
          (sysctl-name "hw.l1icachesize_compat")
          (sysctl-name "hw.l2cachesize_compat")
          (sysctl-name "hw.l3cachesize_compat")
          (sysctl-name "hw.logicalcpu_max")
          (sysctl-name "hw.machine")
          (sysctl-name "hw.ncpu")
          (sysctl-name "hw.nperflevels")
          (sysctl-name "hw.optional.arm.FEAT_BF16")
          (sysctl-name "hw.optional.arm.FEAT_DotProd")
          (sysctl-name "hw.optional.arm.FEAT_FCMA")
          (sysctl-name "hw.optional.arm.FEAT_FHM")
          (sysctl-name "hw.optional.arm.FEAT_FP16")
          (sysctl-name "hw.optional.arm.FEAT_I8MM")
          (sysctl-name "hw.optional.arm.FEAT_JSCVT")
          (sysctl-name "hw.optional.arm.FEAT_LSE")
          (sysctl-name "hw.optional.arm.FEAT_RDM")
          (sysctl-name "hw.optional.arm.FEAT_SHA512")
          (sysctl-name "hw.optional.armv8_2_sha512")
          (sysctl-name "hw.memsize")
          (sysctl-name "hw.pagesize")
          (sysctl-name "hw.packages")
          (sysctl-name "hw.pagesize_compat")
          (sysctl-name "hw.physicalcpu_max")
          (sysctl-name "hw.tbfrequency_compat")
          (sysctl-name "hw.vectorunit")
          (sysctl-name "kern.hostname")
          (sysctl-name "kern.maxfilesperproc")
          (sysctl-name "kern.osproductversion")
          (sysctl-name "kern.osrelease")
          (sysctl-name "kern.ostype")
          (sysctl-name "kern.osvariant_status")
          (sysctl-name "kern.osversion")
          (sysctl-name "kern.secure_kernel")
          (sysctl-name "kern.usrstack64")
          (sysctl-name "kern.version")
          (sysctl-name "sysctl.proc_cputype")
          (sysctl-name-prefix "hw.perflevel")
        )
        """;
    
    /** Escape a path for inclusion inside a seatbelt policy. */
    private static String escapeForSeatbelt(String path) {
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatOutput(String stdout, String stderr) {
        stdout = stdout.trim().replaceAll(ANSI_ESCAPE_PATTERN, "");
        stderr = stderr.trim().replaceAll(ANSI_ESCAPE_PATTERN, "");

        if (stdout.isEmpty() && stderr.isEmpty()) {
            return "";
        }
        if (stderr.isEmpty() || Boolean.parseBoolean(System.getenv("BRK_SUPPRESS_STDERR"))) {
            return stdout;
        }
        if (stdout.isEmpty()) {
            return stderr;
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
            this.output = output;
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
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Returns the current user's home directory as a Path.
     */
    public static Path getHomePath() {
        return Path.of(System.getProperty("user.home"));
    }

    /**
     * Determines if sandboxing is available on the current operating system.
     */
    public static boolean isSandboxAvailable() {
        if (isWindows()) {
            return false;
        }
        if (isMacOs()) {
            return new File("/usr/bin/sandbox-exec").canExecute();
        }
        // TODO Linux
        return false;
    }

    /**
     * Sends a desktop notification asynchronously.
     *
     * @param message  The message body of the notification.
     */
    public void sendNotificationAsync(String message) {
        CompletableFuture.runAsync(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
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
