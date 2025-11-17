package ai.brokk.util;

import ai.brokk.Brokk;
import ai.brokk.gui.Chrome;
import com.google.common.base.Splitter;
import com.sun.management.UnixOperatingSystemMXBean;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class Environment {
    private static final Logger logger = LogManager.getLogger(Environment.class);
    public static final Environment instance = new Environment();

    /** Default timeout for generic shell commands. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    /** Timeout for fast git commands (status, branch, etc.). */
    public static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    /** Timeout for network-heavy git operations (fetch, clone, push, pull). */
    public static final Duration GIT_NETWORK_TIMEOUT = Duration.ofMinutes(5);

    /** Unlimited timeout constant (no timeout guard). */
    public static final Duration UNLIMITED_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE);

    public static String exeName(String base) {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? base + ".exe" : base;
    }

    @FunctionalInterface
    public interface ShellCommandRunner {
        String run(Consumer<String> outputConsumer, Duration timeout) throws SubprocessException, InterruptedException;
    }

    // Default factory creates the real runner. Tests can replace this.
    public static final BiFunction<String, Path, ShellCommandRunner> DEFAULT_SHELL_COMMAND_RUNNER_FACTORY =
            (cmd, projectRoot) -> (outputConsumer, timeout) ->
                    runShellCommandInternal(cmd, projectRoot, false, timeout, outputConsumer, null, Map.of(), null);

    public static BiFunction<String, Path, ShellCommandRunner> shellCommandRunnerFactory =
            DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;

    @Nullable
    private TrayIcon brokkTrayIcon = null;

    private Environment() {}

    /**
     * Runs a shell command with a caller-specified timeout.
     *
     * @param timeout timeout duration; {@code Duration.ZERO} or negative disables the guard
     */
    public String runShellCommand(String command, Path root, Consumer<String> outputConsumer, Duration timeout)
            throws SubprocessException, InterruptedException {
        return shellCommandRunnerFactory.apply(command, root).run(outputConsumer, timeout);
    }

    /**
     * Runs a shell command with optional sandbox and configurable timeout.
     *
     * @param timeout timeout duration; {@code Duration.ZERO} or negative disables the guard
     */
    public String runShellCommand(
            String command, Path root, boolean sandbox, Consumer<String> outputConsumer, Duration timeout)
            throws SubprocessException, InterruptedException {
        return runShellCommandInternal(command, root, sandbox, timeout, outputConsumer, null, Map.of(), null);
    }

    /**
     * Runs a shell command with a caller-specified timeout and project for executor configuration.
     *
     * @param timeout timeout duration; {@code Duration.ZERO} or negative disables the guard
     */
    public String runShellCommand(
            String command,
            Path root,
            Consumer<String> outputConsumer,
            Duration timeout,
            @Nullable ExecutorConfig executorConfig,
            Map<String, String> environment)
            throws SubprocessException, InterruptedException {
        return runShellCommand(command, root, outputConsumer, timeout, executorConfig, environment, null);
    }

    public String runShellCommand(
            String command,
            Path root,
            Consumer<String> outputConsumer,
            Duration timeout,
            @Nullable ExecutorConfig executorConfig,
            Map<String, String> environment,
            @Nullable Consumer<Process> processConsumer)
            throws SubprocessException, InterruptedException {
        // Check if shellCommandRunnerFactory has been overridden for testing.
        // If so, use the factory path (which allows tests to mock/stub).
        // Otherwise, use the direct path with ExecutorConfig and environment support.
        if (shellCommandRunnerFactory != DEFAULT_SHELL_COMMAND_RUNNER_FACTORY) {
            // Test hook: delegate to factory
            return shellCommandRunnerFactory.apply(command, root).run(outputConsumer, timeout);
        }

        // Production path: use the new overload with full support
        return runShellCommandInternal(
                command, root, false, timeout, outputConsumer, executorConfig, environment, processConsumer);
    }

    /** Internal helper that supports running the command in a sandbox when requested. */
    private static String runShellCommandInternal(
            String command,
            Path root,
            boolean sandbox,
            Duration timeout,
            Consumer<String> outputConsumer,
            @Nullable ExecutorConfig executorConfig,
            Map<String, String> environment,
            @Nullable Consumer<Process> processConsumer)
            throws SubprocessException, InterruptedException {
        logger.debug(
                "Running internal `{}` in `{}` (sandbox={}, has-consumer={})",
                command,
                root,
                sandbox,
                processConsumer != null);

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
                        writeRule = "(allow file-write* (subpath \"" + escapeForSeatbelt(absPath) + "\") "
                                + "(subpath \"" + escapeForSeatbelt(realPath) + "\"))";
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

                // Phase 2: Support approved custom executors in sandbox mode
                if (executorConfig != null && ExecutorValidator.isApprovedForSandbox(executorConfig)) {
                    // Use custom executor with sandbox
                    String[] executorCommand = executorConfig.buildCommand(command);
                    String[] sandboxedCommand = new String[executorCommand.length + 4];
                    sandboxedCommand[0] = "sandbox-exec";
                    sandboxedCommand[1] = "-f";
                    sandboxedCommand[2] = policyFile.toString();
                    sandboxedCommand[3] = "--";
                    System.arraycopy(executorCommand, 0, sandboxedCommand, 4, executorCommand.length);
                    shellCommand = sandboxedCommand;

                    logger.info("using custom executor '{}' with sandbox", executorConfig.getDisplayName());
                } else {
                    // Fallback to system default with sandbox
                    shellCommand =
                            new String[] {"sandbox-exec", "-f", policyFile.toString(), "--", "/bin/sh", "-c", command};

                    if (executorConfig != null) {
                        logger.info(
                                "custom executor '{}' not approved for sandbox, using /bin/sh",
                                executorConfig.getDisplayName());
                    }
                }
                // TODO
            } else if (isLinux()) {
                throw new UnsupportedOperationException("sandboxing is not supported yet on Linux");
            } else {
                throw new UnsupportedOperationException("sandboxing is not supported on this OS");
            }
        } else {
            // Phase 1: Support custom executors for non-sandboxed execution
            if (executorConfig != null && executorConfig.isValid()) {
                shellCommand = executorConfig.buildCommand(command);
                logger.info("using custom executor '{}'", executorConfig.getDisplayName());
            } else {
                if (executorConfig != null && !executorConfig.isValid()) {
                    logger.warn("invalid custom executor '{}', using system default", executorConfig);
                }
                // Fall back to system default
                shellCommand = isWindows()
                        ? new String[] {"powershell.exe", "-Command", command}
                        : new String[] {"/bin/sh", "-c", command};
            }
        }

        logger.trace("command: {}", String.join(" ", shellCommand));
        ProcessBuilder pb = createProcessBuilder(root, shellCommand);

        if (!environment.isEmpty()) {
            var expanded = expandEnvMap(environment);
            pb.environment().putAll(expanded);
        }

        Process process;
        try {
            process = pb.start();
            if (processConsumer != null) {
                processConsumer.accept(process);
            }
        } catch (IOException e) {
            var shell = isWindows() ? "cmd.exe" : "/bin/sh";
            throw new StartupException(
                    "unable to start %s in %s for command: `%s` (%s)".formatted(shell, root, command, e.getMessage()),
                    "");
        }

        CompletableFuture<String> stdoutFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getInputStream(), outputConsumer));
        CompletableFuture<String> stderrFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream(), outputConsumer));

        String combinedOutput;
        try {
            boolean finished;
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("Timeout duration cannot be negative: " + timeout);
            } else if (timeout.equals(UNLIMITED_TIMEOUT)) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (!finished) {
                process.destroyForcibly();
                String stdout = stdoutFuture.join();
                String stderr = stderrFuture.join();
                combinedOutput = formatOutput(stdout, stderr);
                throw new TimeoutException(
                        "process '%s' did not complete within %s".formatted(command, timeout), combinedOutput);
            }
        } catch (InterruptedException ie) {
            process.destroyForcibly();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            logger.warn("Process '{}' interrupted.", command);
            throw ie;
        }

        // collect output with timeout to avoid indefinite blocking
        StreamOutput streams = collectStreamOutputs(stdoutFuture, stderrFuture, command);
        combinedOutput = formatOutput(streams.stdout(), streams.stderr());
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            throw new FailureException(
                    "process '%s' signalled error code %d".formatted(command, exitCode), combinedOutput, exitCode);
        }

        return combinedOutput;
    }

    private static String readStream(InputStream in, Consumer<String> outputConsumer) {
        var lines = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
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

    /** Record to hold stdout and stderr output from stream collection. */
    private record StreamOutput(String stdout, String stderr) {}

    /** Collect stdout and stderr from futures with a timeout to avoid indefinite blocking. */
    private static StreamOutput collectStreamOutputs(
            CompletableFuture<String> stdoutFuture, CompletableFuture<String> stderrFuture, String command) {
        String stdout;
        String stderr;
        try {
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Timeout or error collecting output streams for command '{}': {}", command, e.getMessage());
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            stdout = "";
            stderr = "Stream collection timeout or error: " + e.getMessage();
        }
        return new StreamOutput(stdout, stderr);
    }

    private static final String ANSI_ESCAPE_PATTERN = "\\x1B(?:\\[[;\\d]*[ -/]*[@-~]|\\]\\d+;[^\\x07]*\\x07)";

    // Pattern for leading environment variable references like $VAR or ${VAR}
    private static final Pattern LEADING_ENV_VAR_PATTERN =
            Pattern.compile("^\\$(?:\\{([a-zA-Z_][a-zA-Z0-9_]*)}|([a-zA-Z_][a-zA-Z0-9_]*))");

    /** Result of detecting an environment variable reference at the start of a string. */
    public record EnvVarReference(@Nullable String name, boolean defined) {}

    /**
     * Detects a leading environment variable reference (e.g., $HOME or ${HOME}) in the provided text. Returns null if
     * no reference is found.
     */
    public static @Nullable EnvVarReference detectEnvVarReference(@Nullable String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        Matcher m = LEADING_ENV_VAR_PATTERN.matcher(trimmed);
        if (!m.find()) return null;
        String varName = m.group(1) != null ? m.group(1) : m.group(2);
        boolean defined = varName != null && System.getenv(varName) != null;
        return new EnvVarReference(varName, defined);
    }

    /**
     * Expands a leading environment variable reference in the provided text, if defined; otherwise returns the input
     * unchanged.
     */
    public static @Nullable String expandLeadingEnvVar(@Nullable String text) {
        if (text == null) return null;
        Matcher m = LEADING_ENV_VAR_PATTERN.matcher(text);
        if (!m.find()) return text;
        String varName = m.group(1) != null ? m.group(1) : m.group(2);
        String value = System.getenv(varName);
        if (value == null) return text;
        return value + text.substring(m.end());
    }

    /** Returns a copy of the given map with leading env var references expanded in the values, when defined. */
    public static Map<String, String> expandEnvMap(Map<String, String> env) {
        if (env.isEmpty()) return env;
        var result = new HashMap<String, String>(env.size());
        for (var e : env.entrySet()) {
            String v = e.getValue();
            result.put(e.getKey(), Objects.requireNonNullElse(expandLeadingEnvVar(v), v));
        }
        return result;
    }

    /**
     * Seatbelt policy that grants read access everywhere and write access only to explicitly whitelisted roots.
     * Networking remains blocked.
     */
    private static final String READ_ONLY_SEATBELT_POLICY =
            """
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

    /** Base exception for subprocess errors. */
    public abstract static class SubprocessException extends IOException {
        private final String output;

        public SubprocessException(String message, String output) {
            super(message);
            this.output = output;
        }

        public String getOutput() {
            return output;
        }
    }

    /** Exception thrown when a subprocess fails to start. */
    public static class StartupException extends SubprocessException {
        public StartupException(String message, String output) {
            super(message, output);
        }
    }

    /** Exception thrown when a subprocess times out. */
    public static class TimeoutException extends SubprocessException {
        public TimeoutException(String message, String output) {
            super(message, output);
        }
    }

    /** Exception thrown when a subprocess returns a non-zero exit code. */
    public static class FailureException extends SubprocessException {
        private final int exitCode;

        public FailureException(String message, String output, int exitCode) {
            super(message, output);
            this.exitCode = exitCode;
        }

        public int getExitCode() {
            return exitCode;
        }
    }

    /** Determines if the current operating system is Windows. */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** Returns the current user's home directory as a Path. */
    public static Path getHomePath() {
        return Path.of(System.getProperty("user.home"));
    }

    /** Determines if sandboxing is available on the current operating system. */
    public static boolean isSandboxAvailable() {
        if (isWindows()) {
            return false;
        }
        if (isMacOs()) {
            if (new File("/usr/bin/sandbox-exec").canExecute()) {
                return true;
            }
            return existsOnPath("sandbox-exec");
        }
        if (isLinux()) {
            // TODO
            return false;
        }
        return false;
    }

    private static boolean existsOnPath(String executable) {
        var path = System.getenv("PATH");
        if (path != null) {
            for (var dir : Splitter.on(File.pathSeparatorChar).split(path)) {
                if (new File(dir, executable).canExecute()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Computes an adaptive concurrency cap for I/O virtual-thread pools based on system file descriptor limits. Falls
     * back to a conservative CPU-bounded value when limits are unavailable. You can override the computed value via the
     * system property: -Dbrokk.io.maxConcurrency=<N>
     */
    public static int computeAdaptiveIoConcurrencyCap() {
        // Baseline by CPU; we clamp with FD-derived capacity.
        int cpuBound = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

        // Allow a user/system override if provided
        String prop = System.getProperty("brokk.io.maxConcurrency");
        if (prop != null) {
            try {
                int overridden = Integer.parseInt(prop);
                int cap = Math.max(1, overridden);
                logger.info("Using overridden IO virtual-thread cap from system property: {}", cap);
                return cap;
            } catch (NumberFormatException nfe) {
                logger.warn("Invalid brokk.io.maxConcurrency value '{}'; ignoring override", prop);
            }
        }

        try {
            var osMxBean = ManagementFactory.getOperatingSystemMXBean();
            if (osMxBean instanceof UnixOperatingSystemMXBean unix) {
                long max = unix.getMaxFileDescriptorCount();
                long open = unix.getOpenFileDescriptorCount();
                if (max > 0L) {
                    long free = Math.max(0L, max - open);
                    long safety = Math.max(32L, (long) Math.ceil(max * 0.15)); // keep 15% of max + 32 FDs free
                    long usable = Math.max(0L, free - safety);

                    // Assume ~1 FD per concurrent read; be conservative: use only half of the usable budget.
                    int byFd = (int) Math.max(8L, Math.min(usable / 2L, 256L));
                    int cap = Math.min(byFd, cpuBound);

                    logger.info(
                            "Adaptive IO cap from FD limits: maxFD={}, openFD={}, freeFD={}, cap={}",
                            max,
                            open,
                            free,
                            cap);
                    return cap;
                }
            }
        } catch (Throwable t) {
            logger.debug("Could not compute Unix FD limits: {}", t.getMessage());
        }

        // Fallback for non-Unix JDKs or if FD data unavailable
        int fallback = Math.min(cpuBound, 64);
        logger.info("Using fallback IO virtual-thread cap: {}", fallback);
        return fallback;
    }

    /**
     * Sends a desktop notification asynchronously.
     *
     * @param message The message body of the notification.
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
     * Opens the specified URL in the default web browser. Handles common errors like browser unavailability.
     *
     * @param url The URL to open.
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
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            Desktop.getDesktop().browse(new URI(url));
        } catch (UnsupportedOperationException ex) {
            logger.error("Browser not supported on this platform (e.g., WSL): {}", url, ex);
            JOptionPane.showMessageDialog(
                    ancestor,
                    "Sorry, unable to open browser automatically. This is a known problem on WSL.\nPlease visit: "
                            + url,
                    "Browser Unsupported",
                    JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            logger.error("Failed to open URL: {}", url, ex);
            JOptionPane.showMessageDialog(
                    ancestor,
                    "Failed to open the browser. Please visit:\n" + url,
                    "Browser Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
