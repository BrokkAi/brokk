package ai.brokk.util;

import ai.brokk.IConsoleIO.NotificationRole;
import ai.brokk.IContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.project.IProject;
import dev.langchain4j.data.message.ChatMessageType;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class ProjectBuildRunner {
    private static final Logger logger = LogManager.getLogger(ProjectBuildRunner.class);

    private final IProject project;
    private final AtomicReference<CompletableFuture<Void>> warmupBuildFutureRef = new AtomicReference<>();

    public ProjectBuildRunner(IProject project) {
        this.project = project;
    }

    public void cancelWarmupBuildIfRunning() {
        CompletableFuture<Void> future = warmupBuildFutureRef.getAndSet(null);
        if (future != null) {
            logger.info("Canceling running warmup build for project {}", project.getRoot());
            future.cancel(true);
        }
    }

    public CompletableFuture<Void> triggerStartupWarmupBuild(IContextManager cm) {
        CompletableFuture<Void> managedFuture = new CompletableFuture<>();
        if (!warmupBuildFutureRef.compareAndSet(null, managedFuture)) {
            throw new IllegalStateException(
                    "Startup warmup build already in progress for project " + project.getRoot());
        }

        CompletableFuture<Void> underlying;
        try {
            underlying = cm.submitBackgroundTask("Startup warmup build", () -> {
                try {
                    performStartupWarmupBuild(cm);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (RuntimeException e) {
            warmupBuildFutureRef.compareAndSet(managedFuture, null);
            managedFuture.completeExceptionally(e);
            throw e;
        }

        underlying.whenComplete((v, t) -> {
            warmupBuildFutureRef.compareAndSet(managedFuture, null);
            if (t != null) managedFuture.completeExceptionally(t);
            else managedFuture.complete(null);
        });

        managedFuture.whenComplete((v, t) -> {
            if (managedFuture.isCancelled()) {
                underlying.cancel(true);
            }
        });

        return managedFuture;
    }

    private void performStartupWarmupBuild(IContextManager cm) throws InterruptedException {
        // loadBuildDetails is the only way to check "do we know how to build" without possibly blocking on BuildAgent
        Optional<BuildDetails> detailsOpt = project.loadBuildDetails();
        if (detailsOpt.isEmpty()) return;

        BuildDetails details = detailsOpt.get();
        if (details.buildLintCommand().isBlank()) return;

        String buildError = runExplicitCommandInternal(cm.liveContext(), details.buildLintCommand(), details, false)
                .getBuildError();
        boolean success = buildError.isBlank();
        cm.pushContext(c -> c.withBuildResult(success, buildError));
        if (!success) {
            cm.getIo()
                    .showNotification(
                            NotificationRole.ERROR,
                            "Startup warmup build failed. Check the build results in your context.");
        }
    }

    @Blocking
    public String runVerification(IContextManager cm) throws InterruptedException {
        return runVerification(cm, null);
    }

    @Blocking
    public String runVerification(IContextManager cm, @Nullable BuildDetails override) throws InterruptedException {
        AtomicReference<InterruptedException> interrupted = new AtomicReference<>();
        Context updated = cm.pushContext(ctx -> {
            try {
                return runVerification(ctx, override);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(e);
                return ctx;
            }
        });
        InterruptedException ie = interrupted.get();
        if (ie != null) throw ie;
        return updated.getBuildError();
    }

    @Blocking
    public Context runVerification(Context ctx) throws InterruptedException {
        return runVerification(ctx, null, null);
    }

    @Blocking
    public Context runVerification(Context ctx, @Nullable BuildDetails override) throws InterruptedException {
        return runVerification(ctx, override, null);
    }

    @Blocking
    public Context runVerification(
            Context ctx, @Nullable BuildDetails override, @Nullable Collection<ProjectFile> testFilesOverride)
            throws InterruptedException {
        cancelWarmupBuildIfRunning();
        String verificationCommand = BuildTools.determineVerificationCommand(ctx, override, testFilesOverride);
        if (verificationCommand == null || verificationCommand.isBlank()) {
            ctx.getContextManager()
                    .getIo()
                    .llmOutput(
                            "\nNo verification command specified, skipping build/check.",
                            ChatMessageType.CUSTOM,
                            LlmOutputMeta.DEFAULT);
            return ctx;
        }
        try {
            return executeWithLock(() -> runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Blocking
    public Context runExplicitCommand(Context ctx, String command, @Nullable BuildDetails override)
            throws InterruptedException {
        return runExplicitCommandInternal(ctx, command, override, true);
    }

    private Context runExplicitCommandInternal(
            Context ctx, String command, @Nullable BuildDetails override, boolean cancelWarmup)
            throws InterruptedException {
        if (cancelWarmup) {
            cancelWarmupBuildIfRunning();
        }
        if (command.isBlank()) {
            ctx.getContextManager()
                    .getIo()
                    .llmOutput(
                            "\nNo explicit command specified, skipping.",
                            ChatMessageType.CUSTOM,
                            LlmOutputMeta.DEFAULT);
            return ctx.withBuildResult(true, "");
        }
        try {
            return executeWithLock(() -> runExplicitBuildAndUpdateFragmentInternal(ctx, command, override));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Context executeWithLock(Callable<Context> action) throws Exception {
        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (noConcurrentBuilds) {
            BuildLock lock = acquireBuildLock();
            if (lock == null) {
                return action.call();
            }
            try (lock) {
                return action.call();
            }
        }
        return action.call();
    }

    private Context runBuildAndUpdateFragmentInternal(
            Context ctx, String verificationCommand, @Nullable BuildDetails override) throws InterruptedException {
        IContextManager cm = ctx.getContextManager();
        var io = cm.getIo();
        BuildDetails details = override != null ? override : project.awaitBuildDetails();
        @Nullable String testRetriesEnv = System.getenv("BRK_TEST_RETRIES");
        if (testRetriesEnv != null && !testRetriesEnv.isBlank()) {
            return runBuildWithTestRetries(ctx, verificationCommand, details, Integer.parseInt(testRetriesEnv.trim()));
        }
        io.commandStart("Verification", verificationCommand);
        var output = new StringBuilder();
        try {
            Environment.instance.runShellCommand(
                    verificationCommand,
                    project.getRoot(),
                    line -> {
                        output.append(line).append("\n");
                        io.commandOutput(line);
                    },
                    resolveTimeout(project.getRunCommandTimeoutSeconds()),
                    project.getShellConfig(),
                    details.environmentVariables());
            io.commandResult("Verification", verificationCommand, true, output.toString(), null);
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (Environment.SubprocessException e) {
            var fullOutput =
                    output + "\n" + Objects.toString(e.getMessage(), "") + "\n" + Objects.toString(e.getOutput(), "");
            io.commandResult("Verification", verificationCommand, false, fullOutput.strip(), e.getMessage());
            return ctx.withBuildResult(
                    false,
                    BuildOutputProcessor.processForLlm(
                            Objects.toString(e.getMessage(), "") + "\n\n" + Objects.toString(e.getOutput(), ""), cm));
        }
    }

    private Context runBuildWithTestRetries(
            Context ctx, String verificationCommand, BuildDetails details, int maxRetries) throws InterruptedException {
        IContextManager cm = ctx.getContextManager();
        var io = cm.getIo();
        String lintCommand = details.buildLintCommand();
        String testCommand = verificationCommand.equals(lintCommand) ? "" : verificationCommand;
        String combinedCommand = verificationCommand + " (with retries, max " + maxRetries + ")";
        io.commandStart("Verification", combinedCommand);
        var output = new StringBuilder();
        var result = BuildVerifier.verifyWithRetries(
                project, lintCommand, testCommand, maxRetries, details.environmentVariables(), line -> {
                    output.append(line).append("\n");
                    io.commandOutput(line);
                });
        io.commandResult("Verification", combinedCommand, result.success(), output.toString(), null);
        if (result.success()) return ctx.withBuildResult(true, "Build succeeded.");
        else return ctx.withBuildResult(false, BuildOutputProcessor.processForLlm(result.output(), cm));
    }

    private Context runExplicitBuildAndUpdateFragmentInternal(
            Context ctx, String command, @Nullable BuildDetails override) throws InterruptedException {
        IContextManager cm = ctx.getContextManager();
        var io = cm.getIo();
        io.commandStart("Post-Task", command);
        var output = new StringBuilder();
        try {
            BuildDetails details = override != null ? override : project.awaitBuildDetails();
            Environment.instance.runShellCommand(
                    command,
                    project.getRoot(),
                    line -> {
                        output.append(line).append("\n");
                        io.commandOutput(line);
                    },
                    resolveTimeout(project.getTestCommandTimeoutSeconds()),
                    project.getShellConfig(),
                    details.environmentVariables());
            io.commandResult("Post-Task", command, true, output.toString(), null);
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (Environment.SubprocessException e) {
            var fullOutput =
                    output + "\n" + Objects.toString(e.getMessage(), "") + "\n" + Objects.toString(e.getOutput(), "");
            io.commandResult("Post-Task", command, false, fullOutput.strip(), e.getMessage());
            return ctx.withBuildResult(
                    false,
                    BuildOutputProcessor.processForLlm(
                            Objects.toString(e.getMessage(), "") + "\n\n" + Objects.toString(e.getOutput(), ""), cm));
        }
    }

    private static Duration resolveTimeout(long timeoutSeconds) {
        if (timeoutSeconds == -1) return Environment.UNLIMITED_TIMEOUT;
        else if (timeoutSeconds <= 0) return Environment.DEFAULT_TIMEOUT;
        else return Duration.ofSeconds(timeoutSeconds);
    }

    private record BuildLock(FileChannel channel, FileLock lock, Path lockFile) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (lock.isValid()) lock.release();
            } catch (Exception e) {
                logger.debug("Error releasing build lock", e);
            }
            try {
                if (channel.isOpen()) channel.close();
            } catch (Exception e) {
                logger.debug("Error closing build lock channel", e);
            }
        }
    }

    private @Nullable BuildLock acquireBuildLock() {
        Path lockDir = Paths.get(System.getProperty("java.io.tmpdir"), "brokk");
        try {
            Files.createDirectories(lockDir);
        } catch (IOException e) {
            logger.warn("Error creating build lock directory {}", lockDir, e);
            return null;
        }
        String repoNameForLock = getOriginRepositoryName();
        Path lockFile = lockDir.resolve(repoNameForLock + ".lock");
        try {
            var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.lock();
            return new BuildLock(channel, lock, lockFile);
        } catch (IOException ioe) {
            logger.warn("Error acquiring build lock {}", lockFile, ioe);
            return null;
        }
    }

    private String getOriginRepositoryName() {
        var url = project.getRepo().getRemoteUrl();
        if (url == null || url.isBlank())
            return project.getRepo().getGitTopLevel().getFileName().toString();
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        int idx = Math.max(url.lastIndexOf('/'), url.lastIndexOf(':'));
        if (idx >= 0 && idx < url.length() - 1) return url.substring(idx + 1);
        throw new IllegalArgumentException("Unable to parse git repo url " + url);
    }

    public void close() {
        cancelWarmupBuildIfRunning();
    }
}
