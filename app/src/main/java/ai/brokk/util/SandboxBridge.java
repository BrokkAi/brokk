package ai.brokk.util;

import ai.brokk.util.sandbox.SandboxConfig;
import ai.brokk.util.sandbox.SandboxManager;
import ai.brokk.util.sandbox.SandboxUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class SandboxBridge {
    private static final Logger logger = LogManager.getLogger(SandboxBridge.class);

    private final Path projectRoot;
    private final boolean allowNetwork;
    private final @Nullable ExecutorConfig executorConfig;

    public SandboxBridge(Path projectRoot, boolean allowNetwork, @Nullable ExecutorConfig executorConfig) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.allowNetwork = allowNetwork;
        this.executorConfig = executorConfig;
    }

    public boolean isAvailable() {
        try {
            return createSandboxManager().checkDependencies();
        } catch (RuntimeException e) {
            logger.info("Sandbox availability check failed: {}", e.getMessage());
            logger.debug("Sandbox availability check failure details", e);
            return false;
        }
    }

    public SandboxManager createSandboxManager() {
        SandboxManager manager = new SandboxManager(SandboxBridge::runCommand);
        manager.initialize(buildSandboxConfig());
        return manager;
    }

    private SandboxConfig buildSandboxConfig() {
        var filesystem = new SandboxConfig.FilesystemConfig(
                buildDenyReadPatterns(),
                buildAllowWritePatterns(),
                buildDenyWritePatterns(),
                allowGitConfig());
        return new SandboxConfig(filesystem, SandboxConfig.LinuxOptions.defaults()).validate();
    }

    private boolean allowGitConfig() {
        return executorConfig != null && ExecutorValidator.isApprovedForSandbox(executorConfig);
    }

    private List<String> buildAllowWritePatterns() {
        return List.of(projectRoot.toString());
    }

    private List<String> buildDenyReadPatterns() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();

        Set<String> deny = new LinkedHashSet<>();
        for (String fileName : SandboxUtils.DANGEROUS_FILES) {
            deny.add(cwd.resolve(fileName).toString());
            deny.add("**/" + fileName);
        }

        for (String dirName : SandboxUtils.getDangerousDirectories()) {
            deny.add(cwd.resolve(dirName).toString());
            deny.add("**/" + dirName + "/**");
        }

        deny.add(cwd.resolve(".git/hooks").toString());
        deny.add("**/.git/hooks/**");

        if (!allowGitConfig()) {
            deny.add(cwd.resolve(".git/config").toString());
            deny.add("**/.git/config");
        }

        return List.copyOf(deny);
    }

    private List<String> buildDenyWritePatterns() {
        return List.of();
    }

    private static SandboxManager.CommandResult runCommand(List<String> command) throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));

        Process p = pb.start();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Thread outThread = new Thread(() -> copyFully(p.getInputStream(), stdout), "sandbox-bridge-stdout");
        Thread errThread = new Thread(() -> copyFully(p.getErrorStream(), stderr), "sandbox-bridge-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);

        outThread.start();
        errThread.start();

        boolean finished = p.waitFor(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("command timed out");
        }

        outThread.join(1000);
        errThread.join(1000);

        int exit = p.exitValue();
        String outStr = stdout.toString(StandardCharsets.UTF_8);
        String errStr = stderr.toString(StandardCharsets.UTF_8);

        return new SandboxManager.CommandResult(exit, outStr, errStr);
    }

    private static void copyFully(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException ignored) {
        }
    }
}
