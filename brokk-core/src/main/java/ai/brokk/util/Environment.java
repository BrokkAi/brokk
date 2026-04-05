package ai.brokk.util;

import ai.brokk.concurrent.LoggingFuture;
import com.sun.management.UnixOperatingSystemMXBean;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Environment {
    private static final Logger logger = LogManager.getLogger(Environment.class);
    public static final Environment instance = new Environment();

    /** Default timeout for generic shell commands. Overridable via BRK_BUILD_TIMEOUT_SECONDS. */
    public static final Duration DEFAULT_TIMEOUT = resolveDefaultTimeout();

    /**
     * Timeout for fast git commands (status, branch, etc.). Configurable via BRK_GIT_TIMEOUT_SECONDS env var.
     */
    public static final Duration GIT_TIMEOUT = parseGitTimeout();

    /** Timeout for network-heavy git operations (fetch, clone, push, pull). */
    public static final Duration GIT_NETWORK_TIMEOUT = Duration.ofMinutes(5);

    /** Unlimited timeout constant (no timeout guard). */
    public static final Duration UNLIMITED_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE);

    private static final String ANSI_ESCAPE_PATTERN = "\\x1B(?:\\[[;\\d]*[ -/]*[@-~]|\\]\\d+;[^\\x07]*\\x07)";

    private Environment() {}

    private static Duration resolveDefaultTimeout() {
        String override = System.getenv("BRK_BUILD_TIMEOUT_SECONDS");
        if (override != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(override));
            } catch (NumberFormatException e) {
                logger.warn("Invalid BRK_BUILD_TIMEOUT_SECONDS value: '{}'. Using fallback.", override);
            }
        }
        return Duration.ofMinutes(2);
    }

    private static Duration parseGitTimeout() {
        String override = System.getenv("BRK_GIT_TIMEOUT_SECONDS");
        if (override != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(override));
            } catch (NumberFormatException e) {
                logger.warn("Invalid BRK_GIT_TIMEOUT_SECONDS value: '{}'. Using fallback.", override);
            }
        }
        return Duration.ofSeconds(10);
    }

    // ---- Shell command runner ----

    @FunctionalInterface
    public interface ShellCommandRunner {
        String run(Consumer<String> outputConsumer, Duration timeout) throws SubprocessException, InterruptedException;
    }

    /** Default factory creates the real runner. Tests can replace this. */
    public static final BiFunction<String, Path, ShellCommandRunner> DEFAULT_SHELL_COMMAND_RUNNER_FACTORY =
            (cmd, projectRoot) ->
                    (outputConsumer, timeout) -> runShellCommandInternal(cmd, projectRoot, timeout, outputConsumer);

    public static BiFunction<String, Path, ShellCommandRunner> shellCommandRunnerFactory =
            DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;

    /**
     * Runs a shell command with a caller-specified timeout.
     *
     * @param timeout timeout duration; {@code Duration.ZERO} or negative disables the guard
     */
    public String runShellCommand(String command, Path root, Consumer<String> outputConsumer, Duration timeout)
            throws SubprocessException, InterruptedException {
        return shellCommandRunnerFactory.apply(command, root).run(outputConsumer, timeout);
    }

    /** Internal helper that runs the command using the basic system shell. */
    private static String runShellCommandInternal(
            String command, Path root, Duration timeout, Consumer<String> outputConsumer)
            throws SubprocessException, InterruptedException {
        logger.trace("Running internal `{}` in `{}`", command, root);

        ShellConfig activeConfig = ShellConfig.basic();
        String[] shellCommand = activeConfig.buildCommand(command);

        logger.debug("Running: {} = {}", Arrays.toString(shellCommand), String.join(" ", shellCommand));
        ProcessBuilder pb = createProcessBuilder(root, shellCommand);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            var shell = isWindows() ? "cmd.exe" : "/bin/sh";
            throw new StartupException(
                    "unable to start %s in %s for command: `%s` (%s)".formatted(shell, root, command, e.getMessage()),
                    "");
        }

        CompletableFuture<String> stdoutFuture =
                LoggingFuture.supplyAsync(() -> readStream(process.getInputStream(), outputConsumer));
        CompletableFuture<String> stderrFuture =
                LoggingFuture.supplyAsync(() -> readStream(process.getErrorStream(), outputConsumer));

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
                    "process '%s' signaled error code %d".formatted(command, exitCode), combinedOutput, exitCode);
        }

        return combinedOutput;
    }

    // ---- Stream helpers ----

    private static String readStream(InputStream in, Consumer<String> outputConsumer) {
        var lines = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputConsumer.accept(line);
                lines.add(line);
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Stream closed")) {
                // Stream closed is expected when process is killed (timeout/interrupt)
                logger.debug("Stream closed during read (process likely terminated)");
            } else {
                logger.warn("Unexpected IO error reading process stream: {}", e.getMessage());
            }
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

    // ---- OS detection helpers ----

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

    public static String exeName(String base) {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? base + ".exe" : base;
    }

    // ---- Concurrency ----

    /**
     * Computes an adaptive concurrency cap for I/O virtual-thread pools based on system file descriptor limits.
     * Falls back to a conservative CPU-bounded value when limits are unavailable.
     * You can override the computed value via the system property: -Dbrokk.io.maxConcurrency=N
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

    // ---- Exception hierarchy ----

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
}
