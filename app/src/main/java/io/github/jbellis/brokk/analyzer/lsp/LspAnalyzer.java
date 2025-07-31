package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.lsp.domain.QualifiedMethod;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public interface LspAnalyzer extends IAnalyzer, AutoCloseable {

    Logger logger = LoggerFactory.getLogger(LspAnalyzer.class);

    @NotNull
    Path getProjectRoot();

    @NotNull
    String getWorkspace();

    @NotNull
    LspServer getServer();
    
    @NotNull
    default CountDownLatch getWorkspaceReadyLatch() {  
        return this.getServer().getWorkspaceReadyLatch();
    }

    /**
     * @return the target programming language as per the LSP's setting specs, e.g., "java".
     */
    @NotNull
    String getLanguage();

    @Override
    default boolean isCpg() {
        return false;
    }

    @Override
    default boolean isEmpty() {
        // This may not work until the build tool has completed building
        return LspAnalyzerHelper.getAllWorkspaceSymbols(getWorkspace(), getServer()).join().isEmpty();
    }

    /**
     * @return the language-specific configuration options for the LSP.
     */
    default @NotNull Map<String, Object> getInitializationOptions() {
        return Collections.emptyMap();
    }

    /**
     * Transform method node fullName to a stable "resolved" name (e.g. removing lambda suffixes).
     */
    @NotNull String resolveMethodName(@NotNull String methodName);

    /**
     * Possibly remove package names from a type string, or do other language-specific cleanup.
     */
    @NotNull String sanitizeType(@NotNull String typeName);

    @Override
    default @NotNull IAnalyzer update(@NotNull Set<ProjectFile> changedFiles) {
        getServer().update(changedFiles);
        logger.debug("Sent didChangeWatchedFiles notification for {} files.", changedFiles.size());
        return this;
    }

    @Override
    default @NotNull IAnalyzer update() {
        getServer().refreshWorkspace().join();
        return this;
    }

    default boolean isClassInProject(String className) {
        return !LspAnalyzerHelper.findTypesInWorkspace(className, getWorkspace(), getServer()).join().isEmpty();
    }

    @Override
    default @Nullable String getClassSource(@NotNull String classFullName) {
        final var futureTypeSymbols = LspAnalyzerHelper
                .findTypesInWorkspace(classFullName, getWorkspace(), getServer(), false);
        final var exactMatch = getClassSource(futureTypeSymbols);

        if (exactMatch == null) {
            // fallback to the whole file, if any partial matches are present
            return futureTypeSymbols.join().stream()
                    .map(LspAnalyzerHelper::getSourceForSymbol)
                    .flatMap(Optional::stream)
                    .distinct()
                    .findFirst()
                    .orElseGet(() -> {
                        // fallback to the whole file, if any partial matches for parent container are present
                        final var classCleanedName = classFullName.replace('$', '.');
                        if (classCleanedName.contains(".")) {
                            final var parentContainer = classCleanedName.substring(0, classCleanedName.lastIndexOf('.'));
                            return LspAnalyzerHelper.findTypesInWorkspace(parentContainer, getWorkspace(), getServer())
                                    .join()
                                    .stream()
                                    .map(LspAnalyzerHelper::getSourceForSymbol)
                                    .flatMap(Optional::stream)
                                    .distinct()
                                    .findFirst()
                                    .orElse(null);
                        } else {
                            return null;
                        }
                    });
        } else {
            return exactMatch;
        }
    }

    private @Nullable String getClassSource(CompletableFuture<List<WorkspaceSymbol>> typeSymbols) {
        return typeSymbols
                .thenApply(symbols ->
                        symbols.stream()
                                .map(symbol -> {
                                    final var eitherLocation = symbol.getLocation();
                                    if (eitherLocation.isLeft()) {
                                        final Location location = eitherLocation.getLeft();
                                        return LspAnalyzerHelper.getFullSymbolRange(getServer(), location)
                                                .join()
                                                .stream()
                                                .flatMap(range ->
                                                        LspAnalyzerHelper.getSourceForURIAndRange(range, location.getUri()).stream()
                                                ).findFirst();
                                    } else {
                                        return Optional.<String>empty();
                                    }
                                })
                                .flatMap(Optional::stream)
                )
                .join()
                .findFirst()
                .orElse(null);
    }

    @Override
    default @NotNull Optional<String> getMethodSource(@NotNull String fqName) {
        return LspAnalyzerHelper.determineMethodName(fqName, this::resolveMethodName)
                .map(qualifiedMethodInfo ->
                        LspAnalyzerHelper.findMethodSymbol(
                                qualifiedMethodInfo.containerFullName(),
                                qualifiedMethodInfo.methodName(),
                                getWorkspace(),
                                getServer(),
                                this::resolveMethodName
                        ).thenApply(maybeSymbol ->
                                maybeSymbol.stream()
                                        .map(LspAnalyzerHelper::getSourceForSymbolDefinition)
                                        .flatMap(Optional::stream)
                                        .distinct()
                                        .collect(Collectors.joining("\n\n"))
                        ).join()
                ).filter(x -> !x.isBlank());
    }

    @Override
    default @NotNull List<CodeUnit> getAllDeclarations() {
        return LspAnalyzerHelper
                .getAllWorkspaceSymbols(getWorkspace(), getServer())
                .thenApply(symbols ->
                        symbols.
                                stream()
                                .filter(s -> LspAnalyzerHelper.TYPE_KINDS.contains(s.getKind()))
                                .map(this::codeUnitForWorkspaceSymbolOfType)
                ).join()
                .toList();
    }

    @Override
    default @NotNull Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        return LspAnalyzerHelper.getWorkspaceSymbolsInFile(getServer(), file.absPath())
                .thenApply(symbols ->
                        symbols.stream().map(this::codeUnitForWorkspaceSymbolOfType)
                )
                .join()
                .collect(Collectors.toSet());
    }

    @Override
    default @NotNull Optional<ProjectFile> getFileFor(@NotNull String fqName) {
        return LspAnalyzerHelper.typesByName(fqName, getWorkspace(), getServer()).
                thenApply(symbols ->
                        symbols.map(symbol -> {
                            final var symbolUri = URI.create(LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation()));
                            return new ProjectFile(getProjectRoot(), getProjectRoot().relativize(Path.of(symbolUri).toAbsolutePath()));
                        })
                ).join()
                .findFirst();
    }

    @Override
    default @NotNull Map<String, List<CallSite>> getCallgraphTo(@NotNull String methodName, int depth) {
        final Optional<QualifiedMethod> methodNameInfo = LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(className, name, getWorkspace(), getServer(), this::resolveMethodName).join();
            functionSymbols
                    .stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod ->
                            LspCallGraphHelper.getCallers(getServer(), originMethod)
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
    default @NotNull Map<String, List<CallSite>> getCallgraphFrom(@NotNull String methodName, int depth) {
        final Optional<QualifiedMethod> methodNameInfo = LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(className, name, getWorkspace(), getServer(), this::resolveMethodName).join();
            functionSymbols
                    .stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod ->
                            LspCallGraphHelper.getCallees(getServer(), originMethod)
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
        final var projectFile = new ProjectFile(this.getProjectRoot(), this.getProjectRoot().relativize(uri));
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

    default CodeUnit codeUnitForWorkspaceSymbolOfType(WorkspaceSymbol symbol) {
        final var uri = Path.of(URI.create(symbol.getLocation().getLeft().getUri()));
        final var projectFile = new ProjectFile(this.getProjectRoot(), this.getProjectRoot().relativize(uri));
        final var codeUnitKind = LspAnalyzerHelper.codeUnitForSymbolKind(symbol.getKind());
        final var containerName = Optional.ofNullable(symbol.getContainerName()).orElse("");

        final var lastDot = containerName.lastIndexOf('.');
        final String shortNamePrefix;
        if (lastDot > 0 && codeUnitKind != CodeUnitType.CLASS && codeUnitKind != CodeUnitType.MODULE) {
            shortNamePrefix = containerName.substring(lastDot + 1);
        } else {
            shortNamePrefix = "";
        }

        return new CodeUnit(
                projectFile,
                codeUnitKind,
                containerName,
                shortNamePrefix + symbol.getName()
        );
    }

}
