package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.lsp.LspClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Java analyzer based on TreeSitter with underlying call graph, points-to, and type hierarchy analysis supplied by
 * Eclipse's JDT LSP.
 *
 * @see io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer
 * @see JdtClient
 */
public class JavaAnalyzer extends JavaTreeSitterAnalyzer
        implements CanCommunicate, CallGraphProvider, UsagesProvider, LintingProvider {

    private static final Logger logger = LoggerFactory.getLogger(JavaAnalyzer.class);
    private final CompletableFuture<JdtClient> jdtClientFuture;
    private volatile @Nullable JdtClient jdtClient;
    private @Nullable IConsoleIO io;

    protected JavaAnalyzer(IProject project, CompletableFuture<JdtClient> jdtClientFuture) {
        super(project);
        this.jdtClientFuture = jdtClientFuture;
        this.jdtClientFuture.whenComplete((client, throwable) -> {
            if (throwable != null) {
                logger.error("Failed to initialize JDT Language Server client", throwable);
                return;
            }
            this.jdtClient = client;
            if (this.io != null) {
                client.setIo(this.io);
            }
        });
    }

    @Override
    public void setIo(IConsoleIO io) {
        this.io = io;
        var client = this.jdtClient;
        if (client != null) {
            client.setIo(io);
        } else {
            jdtClientFuture.thenAccept(analyzer -> analyzer.setIo(io));
        }
    }

    private <T> T safeBlockingLspOperation(Function<LspClient, T> function, T defaultValue, String errMessage) {
        try {
            var client = awaitClient();
            return function.apply(client);
        } catch (RuntimeException e) {
            logger.error(errMessage, e);
            if (io != null) io.systemOutput(errMessage);
            return defaultValue;
        }
    }

    private void safeAsyncLspOperation(Consumer<LspClient> clientConsumer, String asyncTaskName, String errMessage) {
        try {
            CompletableFuture.runAsync(() -> {
                long lspStart = System.currentTimeMillis();
                var client = awaitClient();
                clientConsumer.accept(client);
                long lspDur = System.currentTimeMillis() - lspStart;
                logger.debug("JavaAnalyzer {} - async: Completed in {} ms", asyncTaskName, lspDur);
            });
        } catch (RuntimeException e) {
            logger.error(errMessage, e);
            if (io != null) io.systemOutput(errMessage);
        }
    }

    private JdtClient awaitClient() {
        var client = this.jdtClient;
        if (client != null) {
            return client;
        }
        try {
            client = jdtClientFuture.join();
            this.jdtClient = client;
            return client;
        } catch (Exception e) {
            throw new RuntimeException("Failed waiting for JDT Language Server client.", e);
        }
    }

    @Override
    public List<CodeUnit> getUses(String fqName) {
        return safeBlockingLspOperation(
                client -> client.getUses(fqName),
                Collections.emptyList(),
                "Unable to determine symbol usages due to error in language server!");
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        return safeBlockingLspOperation(
                client -> client.getCallgraphTo(methodName, depth),
                Collections.emptyMap(),
                "Unable to determine symbol call graph due to error in language server!");
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        return safeBlockingLspOperation(
                client -> client.getCallgraphFrom(methodName, depth),
                Collections.emptyMap(),
                "Unable to determine symbol call graph due to error in language server!");
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        safeAsyncLspOperation(
                client -> client.update(changedFiles),
                "LSP update (" + changedFiles.size() + ")",
                "Unable to update language server due to error!");

        long tsStart = System.currentTimeMillis();
        IAnalyzer result = super.update(changedFiles);
        long tsDur = System.currentTimeMillis() - tsStart;
        logger.debug("JavaAnalyzer TreeSitter update: {} files in {} ms", changedFiles.size(), tsDur);

        return result;
    }

    @Override
    public IAnalyzer update() {
        safeAsyncLspOperation(
                LspClient::update, "LSP update (unspecified)", "Unable to update language server due to error!");

        long tsStart = System.currentTimeMillis();
        IAnalyzer result = super.update();
        long tsDur = System.currentTimeMillis() - tsStart;
        logger.debug("JavaAnalyzer TreeSitter full incremental update in {} ms", tsDur);

        return result;
    }

    @Override
    public LintResult lintFiles(List<ProjectFile> files) {
        return safeBlockingLspOperation(
                client -> client.lintFiles(files),
                new LintResult(Collections.emptyList()),
                "Unable to lint files due to error in language server!");
    }

    public Path getProjectRoot() {
        return this.getProject().getRoot();
    }

    public static JavaAnalyzer create(IProject project) {
        final var jdtAnalyzerFuture = CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Creating JDT LSP Analyzer in the background.");
                return new JdtClient(project);
            } catch (IOException e) {
                logger.error("Exception encountered while creating JDT analyzer");
                throw new RuntimeException(e);
            }
        });
        return new JavaAnalyzer(project, jdtAnalyzerFuture);
    }
}
