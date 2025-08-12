package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IProject;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
    private @Nullable IConsoleIO io;
    private final CompletableFuture<Boolean> jdtAnalyzerFuture;

    public JavaAnalyzer(IProject project) {
        this(project, project.getExcludedDirectories());
    }

    public JavaAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, excludedFiles);
        this.jdtAnalyzerFuture = CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("Creating JDT LSP Analyzer in the background.");
                this.jdtAnalyzer = new JdtAnalyzer(project.getRoot(), excludedFiles);
                this.jdtAnalyzer.setIo(io);
                return true;
            } catch (IOException e) {
                log.error("Exception encountered while creating JDT analyzer");
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> isAdvancedAnalysisReady() {
        return this.jdtAnalyzerFuture;
    }

    @Override
    public void setIo(IConsoleIO io) {
        this.io = io;
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
}
