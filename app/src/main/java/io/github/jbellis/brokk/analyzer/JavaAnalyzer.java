package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IProject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * A Java analyzer based on TreeSitter with underlying call graph, points-to, and type hierarchy analysis supplied by
 * Eclipse's JDT LSP. Use {@link HasDelayedCapabilities#isAdvancedAnalysisReady} to determine when the JDT LSP-based
 * analysis is available.
 *
 * @see io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer
 * @see io.github.jbellis.brokk.analyzer.JdtAnalyzer
 */
public class JavaAnalyzer extends JavaTreeSitterAnalyzer implements HasDelayedCapabilities, CanCommunicate {

    private @Nullable JdtAnalyzer jdtAnalyzer;
    private final CompletableFuture<JdtAnalyzer> jdtAnalyzerFuture;

    private JavaAnalyzer(IProject project, CompletableFuture<JdtAnalyzer> jdtAnalyzerFuture) {
        super(project);
        this.jdtAnalyzerFuture = jdtAnalyzerFuture.thenApply(analyzer -> {
            this.jdtAnalyzer = analyzer;
            return analyzer;
        });
    }

    @Override
    public CompletableFuture<Boolean> isAdvancedAnalysisReady() {
        // If no exception raised, we're good to use the JDT analyzer
        return this.jdtAnalyzerFuture.handleAsync(
                (JdtAnalyzer result, @Nullable Throwable exception) -> exception == null);
    }

    @Override
    public void setIo(IConsoleIO io) {
        if (this.jdtAnalyzer != null) this.jdtAnalyzer.setIo(io);
        else if (!jdtAnalyzerFuture.isDone())
            jdtAnalyzerFuture.thenApply(analyzer -> {
                analyzer.setIo(io);
                return analyzer;
            });
    }

    @Override
    public List<CodeUnit> getUses(String fqName) {
        if (jdtAnalyzer != null) {
            return jdtAnalyzer.getUses(fqName);
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        if (jdtAnalyzer != null) {
            return jdtAnalyzer.getCallgraphTo(methodName, depth);
        } else {
            return Collections.emptyMap();
        }
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        if (jdtAnalyzer != null) {
            return jdtAnalyzer.getCallgraphTo(methodName, depth);
        } else {
            return Collections.emptyMap();
        }
    }

    @Override
    public FunctionLocation getFunctionLocation(String fqMethodName, List<String> paramNames) {
        if (jdtAnalyzer != null) {
            return jdtAnalyzer.getFunctionLocation(fqMethodName, paramNames);
        } else {
            return super.getFunctionLocation(fqMethodName, paramNames);
        }
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        if (jdtAnalyzer != null) {
            return jdtAnalyzer.update(changedFiles);
        } else {
            return super.update(changedFiles);
        }
    }

    @Override
    public IAnalyzer update() {
        if (jdtAnalyzer != null) {
            return jdtAnalyzer.update();
        } else {
            return super.update();
        }
    }

    public Path getProjectRoot() {
        return this.getProject().getRoot();
    }

    public static JavaAnalyzer create(IProject project) {
        final var jdtAnalyzerFuture = CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("Creating JDT LSP Analyzer in the background.");
                return new JdtAnalyzer(project);
            } catch (IOException e) {
                log.error("Exception encountered while creating JDT analyzer");
                throw new RuntimeException(e);
            }
        });
        return new JavaAnalyzer(project, jdtAnalyzerFuture);
    }
}
