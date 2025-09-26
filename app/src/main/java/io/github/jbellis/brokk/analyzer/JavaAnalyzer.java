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

    private void safeBlockingLspOperation(Consumer<LspClient> clientConsumer, String errMessage) {
        try {
            var client = awaitClient();
            clientConsumer.accept(client);
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
        safeBlockingLspOperation(
                client -> client.update(changedFiles), "Unable to update language server due to error!");
        return super.update(changedFiles);
    }

    @Override
    public IAnalyzer update() {
        safeBlockingLspOperation(LspClient::update, "Unable to update language server due to error!");
        return super.update();
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
