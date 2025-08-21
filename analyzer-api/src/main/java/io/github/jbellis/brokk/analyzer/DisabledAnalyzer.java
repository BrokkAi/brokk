package io.github.jbellis.brokk.analyzer;

import java.util.*;

public class DisabledAnalyzer
        implements IAnalyzer, UsagesProvider, SourceCodeProvider, SkeletonProvider, CallGraphProvider {

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return Collections.emptyList();
    }

    @Override
    public List<CodeUnit> getMembersInClass(String fqClass) {
        return Collections.emptyList();
    }

    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        return Collections.emptySet();
    }

    @Override
    public List<CodeUnitRelevance> getRelevantCodeUnits(Map<String, Double> seedClassWeights, int k, boolean reversed) {
        return Collections.emptyList();
    }

    @Override
    public Optional<String> getSkeleton(String className) {
        return Optional.empty();
    }

    @Override
    public Optional<String> getSkeletonHeader(String className) {
        return Optional.empty();
    }

    @Override
    public Optional<ProjectFile> getFileFor(String fqcn) {
        return Optional.empty();
    }

    @Override
    public List<CodeUnit> searchDefinitions(String pattern) {
        return Collections.emptyList();
    }

    @Override
    public List<CodeUnit> getUses(String symbol) {
        return Collections.emptyList();
    }

    @Override
    public Optional<String> getMethodSource(String methodName) {
        return Optional.empty();
    }

    @Override
    public String getClassSource(String className) {
        return "";
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        return Collections.emptyMap();
    }

    @Override
    public Set<String> getSymbols(Set<CodeUnit> sources) {
        return Collections.emptySet();
    }
}
