package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzer;
import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzerHelper;
import io.github.jbellis.brokk.analyzer.lsp.LspCallGraphHelper;
import io.github.jbellis.brokk.analyzer.lsp.SharedLspServer;
import io.github.jbellis.brokk.analyzer.lsp.domain.QualifiedMethod;
import io.github.jbellis.brokk.analyzer.lsp.jdt.JdtProjectHelper;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class JdtAnalyzer implements LspAnalyzer {

    private final Logger logger = LoggerFactory.getLogger(JdtAnalyzer.class);

    @NotNull
    private final Path projectRoot;
    @NotNull
    private final String workspace;
    @NotNull
    private final SharedLspServer sharedServer;

    /**
     * Creates an analyzer for a specific project workspace.
     *
     * @param projectPath   The path to the Java project to be analyzed.
     * @param excludedPaths A set of glob patterns to exclude from analysis (e.g., "build", "**\/target").
     * @throws IOException if the server cannot be started.
     */
    public JdtAnalyzer(Path projectPath, Set<String> excludedPaths) throws IOException {
        this.projectRoot = projectPath.toAbsolutePath().normalize();
        if (!this.projectRoot.toFile().exists()) {
            throw new FileNotFoundException("Project directory does not exist: " + projectRoot);
        } else {
            JdtProjectHelper.ensureProjectConfiguration(this.projectRoot);
            this.sharedServer = SharedLspServer.getInstance();
            this.sharedServer.registerClient(this.projectRoot, excludedPaths);
            this.sharedServer.refreshWorkspace().join();
            this.workspace = this.projectRoot.toUri().toString();
        }
    }

    public boolean isClassInProject(String className) {
        return !LspAnalyzerHelper.findTypesInWorkspace(className, workspace, sharedServer).join().isEmpty();
    }

    @Override
    @Nullable
    public String getClassSource(String classFullName) {
        return LspAnalyzerHelper
                .findTypesInWorkspace(classFullName, workspace, sharedServer)
                .thenApply(LspAnalyzerHelper::getSourceForSymbol).join().orElse(null);
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        return LspAnalyzerHelper.determineMethodName(fqName, this::resolveMethodName).map(qualifiedMethodInfo ->
                LspAnalyzerHelper.findMethodSymbol(qualifiedMethodInfo.containerFullName(), qualifiedMethodInfo.methodName(), workspace, sharedServer, this::resolveMethodName)
                        .thenApply(maybeSymbol ->
                                maybeSymbol.stream()
                                        .map(LspAnalyzerHelper::getSourceForSymbolDefinition)
                                        .flatMap(Optional::stream)
                                        .collect(Collectors.joining("\n\n"))
                        ).join()
        ).filter(x -> !x.isBlank());
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        final Optional<QualifiedMethod> methodNameInfo = LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(className, name, workspace, sharedServer, this::resolveMethodName).join();
            functionSymbols
                    .stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod ->
                            LspCallGraphHelper.getCallers(sharedServer, originMethod)
                                    .join()
                                    .forEach(incomingCall -> callGraphEntry(originMethod, callGraph, key, incomingCall, depth))
                    );
            return callGraph;
        } else {
            logger.warn("Method name not found: {}", methodName);
            return new HashMap<>();
        }
    }

    private Map<String, List<CallSite>> getCallgraphTo(CallSite callSite, int depth) {
        if (depth > 0) {
            return getCallgraphTo(callSite.target().fqName(), depth - 1);
        } else {
            return new HashMap<>();
        }
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        final Optional<QualifiedMethod> methodNameInfo = LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(className, name, workspace, sharedServer, this::resolveMethodName).join();
            functionSymbols
                    .stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod ->
                            LspCallGraphHelper.getCallees(sharedServer, originMethod)
                                    .join()
                                    .forEach(outgoingCall -> callGraphEntry(originMethod, callGraph, key, outgoingCall, depth))
                    );
            return callGraph;
        } else {
            logger.warn("Method name not found: {}", methodName);
            return new HashMap<>();
        }
    }

    private Map<String, List<CallSite>> getCallgraphFrom(CallSite callSite, int depth) {
        if (depth > 0) {
            return getCallgraphFrom(callSite.target().fqName(), depth - 1);
        } else {
            return new HashMap<>();
        }
    }

    private void callGraphEntry(Location originMethod, Map<String, List<CallSite>> callGraph, String key, Object someCall, int depth) {
        if (someCall instanceof CallHierarchyIncomingCall incomingCall) {
            final CallSite newCallSite = registerCallItem(key, true, originMethod, incomingCall.getFrom(), incomingCall.getFromRanges(), callGraph);
            // Continue search, and add any new entries
            getCallgraphTo(newCallSite, depth - 1).forEach((k, v) -> {
                final var nestedCallSites = callGraph.getOrDefault(k, new ArrayList<>());
                nestedCallSites.addAll(v);
                callGraph.put(k, nestedCallSites);
            });
        } else if (someCall instanceof CallHierarchyOutgoingCall outgoingCall) {
            final CallSite newCallSite = registerCallItem(key, false, originMethod, outgoingCall.getTo(), outgoingCall.getFromRanges(), callGraph);
            // Continue search, and add any new entries
            getCallgraphFrom(newCallSite, depth - 1).forEach((k, v) -> {
                final var nestedCallSites = callGraph.getOrDefault(k, new ArrayList<>());
                nestedCallSites.addAll(v);
                callGraph.put(k, nestedCallSites);
            });
        }
    }

    private CallSite registerCallItem(String key, boolean isIncoming, Location originMethod, CallHierarchyItem callItem, List<Range> ranges, Map<String, List<CallSite>> callGraph) {
        final var uri = Path.of(URI.create(callItem.getUri()));
        final var projectFile = new ProjectFile(this.projectRoot, this.projectRoot.relativize(uri));
        final var containerInfo = callItem.getDetail() == null ? "" : callItem.getDetail();  // TODO: Not sure if null means empty or external
        final var cu = new CodeUnit(
                projectFile,
                LspAnalyzerHelper.codeUnitForSymbolKind(callItem.getKind()),
                containerInfo,
                resolveMethodName(callItem.getName())
        );
        final var sourceLine = LspAnalyzerHelper.getCodeForCallSite(isIncoming, originMethod, callItem, ranges).orElse(callItem.getName() + "(...)");
        final var callSites = callGraph.getOrDefault(key, new ArrayList<>());
        final var newCallSite = new CallSite(cu, sourceLine);
        callSites.add(newCallSite);
        callGraph.put(key, callSites);
        return newCallSite;
    }


    /**
     * Strips the method signature (parentheses and parameters) from a method name string.
     *
     * @param methodName The full method name from the symbol object (e.g., "myMethod(int)").
     * @return The clean method name without the signature (e.g., "myMethod").
     */
    @Override
    @NotNull
    public String resolveMethodName(@NotNull String methodName) {
        final var cleanedName = methodName.replace('$', '.');
        int parenIndex = cleanedName.indexOf('(');

        // If a parenthesis is found, return the part of the string before it.
        if (parenIndex != -1) {
            return cleanedName.substring(0, parenIndex);
        }

        // Otherwise, return the original string.
        return cleanedName;
    }


    @Override
    public void close() {
        sharedServer.unregisterClient(this.projectRoot);
    }
}