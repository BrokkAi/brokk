package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.lsp.domain.QualifiedMethod;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A collection of largely pure functions to help navigate the given LSP server in a language-agnostic way.
 */
public final class LspAnalyzerHelper {

    static Logger logger = LoggerFactory.getLogger(LspAnalyzerHelper.class.getName());

    public static final Set<SymbolKind> TYPE_KINDS = Set.of(
            SymbolKind.Class,
            SymbolKind.Interface,
            SymbolKind.Enum,
            SymbolKind.Struct
    );

    public static final Set<SymbolKind> METHOD_KINDS = Set.of(
            SymbolKind.Method,
            SymbolKind.Function,
            SymbolKind.Constructor
    );

    public static final Set<SymbolKind> MODULE_KINDS = Set.of(
            SymbolKind.Module,
            SymbolKind.Namespace,
            SymbolKind.Package
    );

    @NotNull
    public static CodeUnitType codeUnitForSymbolKind(@NotNull SymbolKind symbolKind) {
        if (METHOD_KINDS.contains(symbolKind)) return CodeUnitType.FUNCTION;
        else if (TYPE_KINDS.contains(symbolKind)) return CodeUnitType.CLASS;
        else if (MODULE_KINDS.contains(symbolKind)) return CodeUnitType.MODULE;
        else return CodeUnitType.FIELD;
    }

    /**
     * Helper to extract the URI string from the nested Either type in a WorkspaceSymbol's location.
     */
    @NotNull
    public static String getUriStringFromLocation(@NotNull Either<Location, WorkspaceSymbolLocation> locationEither) {
        return locationEither.isLeft()
                ? locationEither.getLeft().getUri()
                : locationEither.getRight().getUri();
    }

    @NotNull
    public static Optional<String> getSourceForURIAndRange(@NotNull Range range, @NotNull String uriString) {
        return getSourceForUriString(uriString).map(fullSource -> getSourceForRange(fullSource, range));
    }

    @NotNull
    public static Optional<String> getSourceForSymbol(@NotNull WorkspaceSymbol symbol) {
        final String uriString = getUriStringFromLocation(symbol.getLocation());
        return getSourceForUriString(uriString);
    }

    @NotNull
    public static Optional<String> getSourceForUriString(@NotNull String uriString) {
        try {
            final Path filePath = Paths.get(new URI(uriString));
            return Optional.of(Files.readString(filePath));
        } catch (IOException | URISyntaxException e) {
            logger.error("Failed to read source for URI '{}'", uriString, e);
            return Optional.empty();
        }
    }

    @NotNull
    public static Optional<String> getCodeForCallSite(
            boolean isIncoming,
            @NotNull Location originalMethod,
            @NotNull CallHierarchyItem callSite,
            @NotNull List<Range> ranges
    ) {
        try {
            // the ranges describe callsite or originalMethod depending on direction
            final String targetFile = isIncoming ? callSite.getUri() : originalMethod.getUri();
            final Path filePath = Paths.get(new URI(targetFile));
            return Optional.of(Files.readString(filePath))
                    .map(source -> {
                        if (ranges.isEmpty()) {
                            return getSourceForRange(source, callSite.getSelectionRange());
                        } else {
                            return getSourceForRange(source, ranges.getFirst());
                        }
                    });
        } catch (IOException | URISyntaxException e) {
            logger.error("Failed to read source for symbol '{}' at {}", callSite.getName(), callSite.getUri(), e);
            return Optional.empty();
        }
    }

    /**
     * Gets the precise source code for a symbol's definition using its Range.
     *
     * @param symbol The symbol to get the source for.
     * @return An Optional containing the source code snippet, or empty if no range is available.
     */
    @NotNull
    public static Optional<String> getSourceForSymbolDefinition(@NotNull WorkspaceSymbol symbol) {
        if (symbol.getLocation().isLeft()) {
            Location location = symbol.getLocation().getLeft();
            return getSourceForSymbol(symbol).map(fullSource -> getSourceForRange(fullSource, location.getRange()));
        }

        logger.warn("Cannot get source for symbol '{}' because its location has no Range information.", symbol.getName());
        return Optional.empty();
    }

    /**
     * A helper that extracts a block of text from a string based on LSP Range data.
     */
    @NotNull
    public static String getSourceForRange(@NotNull String fileContent, @NotNull Range range) {
        String[] lines = fileContent.split("\\R", -1); // Split by any line break
        int startLine = range.getStart().getLine();
        int endLine = range.getEnd().getLine();
        int startChar = range.getStart().getCharacter();
        int endChar = range.getEnd().getCharacter();

        if (startLine == endLine) {
            return lines[startLine].substring(startChar, endChar);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(lines[startLine].substring(startChar)).append(System.lineSeparator());

        for (int i = startLine + 1; i < endLine; i++) {
            sb.append(lines[i]).append(System.lineSeparator());
        }

        if (endChar > 0) {
            sb.append(lines[endLine], 0, endChar);
        }

        return sb.toString();
    }

    /**
     * Gets a list of all symbols defined within a specific file.
     *
     * @param filePath The path to the file to analyze.
     * @return A CompletableFuture that will resolve with the server's response, which is an
     * 'Either' containing a list of SymbolInformation (older format) or DocumentSymbol (newer, hierarchical format).
     */
    @NotNull
    public static CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> getSymbolsInFile(
            @NotNull LspServer sharedServer,
            @NotNull Path filePath
    ) {
        logger.info("Querying for document symbols in {}", filePath);
        return sharedServer.query(server -> {
            DocumentSymbolParams params = new DocumentSymbolParams(
                    new TextDocumentIdentifier(filePath.toUri().toString())
            );
            return server.getTextDocumentService().documentSymbol(params).join();
        });
    }

    @NotNull
    public static WorkspaceSymbol documentToWorkspaceSymbol(
            @NotNull DocumentSymbol documentSymbol,
            @NotNull String uriString
    ) {
        return new WorkspaceSymbol(
                documentSymbol.getName(),
                documentSymbol.getKind(),
                Either.forLeft(new Location(uriString, documentSymbol.getRange()))
        );
    }

    /**
     * Recursively searches a tree of DocumentSymbol objects for a symbol with a specific name and kind.
     */
    @NotNull
    public static List<DocumentSymbol> findSymbolsInTree(
            @NotNull List<DocumentSymbol> symbols,
            @NotNull String name,
            @NotNull Set<SymbolKind> kinds,
            @NotNull Function<String, String> resolveMethodName
    ) {
        return symbols.stream().flatMap(symbol -> {
                    if (resolveMethodName.apply(symbol.getName()).equals(name) && kinds.contains(symbol.getKind())) {
                        return Stream.of(symbol);
                    } else {
                        if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                            return findSymbolsInTree(symbol.getChildren(), name, kinds, resolveMethodName).stream();
                        } else {
                            return Stream.empty();
                        }
                    }
                }
        ).toList();
    }

    /**
     * Recursively searches a tree of DocumentSymbols to find the one whose name (`selectionRange`)
     * contains the given position. This version returns the full DocumentSymbol.
     */
    public static @NotNull Optional<DocumentSymbol> findSymbolInTree(List<DocumentSymbol> symbols, Position position) {
        for (DocumentSymbol symbol : symbols) {
            if (isPositionInRange(symbol.getSelectionRange(), position)) {
                return Optional.of(symbol);
            }
            if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                Optional<DocumentSymbol> found = findSymbolInTree(symbol.getChildren(), position);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Takes a location (e.g., from a workspace/symbol search) and finds the full range
     * for the symbol at that position by performing a more detailed documentSymbol search.
     *
     * @param location The location of the symbol's name.
     * @return A CompletableFuture resolving to the full range of the symbol's definition.
     */
    public static CompletableFuture<Optional<Range>> getFullSymbolRange(@NotNull LspServer sharedServer, @NotNull Location location) {
        final Path filePath = Paths.get(URI.create(location.getUri()));
        final Position position = location.getRange().getStart();

        return getSymbolsInFile(sharedServer, filePath).thenApply(eithers ->
                eithers.stream()
                        .map(either -> either.isRight() ?
                                findRangeInTree(Collections.singletonList(either.getRight()), position) : Optional.<Range>empty())
                        .flatMap(Optional::stream)
                        .findFirst()
        );
    }

    /**
     * Recursively searches a tree of DocumentSymbols to find the one whose name (`selectionRange`)
     * contains the given position, and returns its full body range (`range`).
     */
    private static Optional<Range> findRangeInTree(List<DocumentSymbol> symbols, Position position) {
        for (DocumentSymbol symbol : symbols) {
            // Check if the symbol's name range contains the position
            if (isPositionInRange(symbol.getSelectionRange(), position)) {
                return Optional.of(symbol.getRange()); // Return the full block range
            }
            // Recurse into children
            if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                Optional<Range> found = findRangeInTree(symbol.getChildren(), position);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    public static boolean isPositionInRange(Range range, Position position) {
        if (position.getLine() < range.getStart().getLine() || position.getLine() > range.getEnd().getLine()) {
            return false;
        } else if (position.getLine() == range.getStart().getLine() && position.getCharacter() < range.getStart().getCharacter()) {
            return false;
        } else {
            return position.getLine() != range.getEnd().getLine() || position.getCharacter() <= range.getEnd().getCharacter();
        }
    }

    /**
     * Finds symbols within this analyzer's specific workspace using the modern WorkspaceSymbol type.
     *
     * @param symbolName   The name of the symbol to search for.
     * @param workspace    The name of the current workspace.
     * @param sharedServer The LSP server to query.
     * @return A CompletableFuture that will be completed with a list of symbols
     * found only within this instance's project path.
     */
    @NotNull
    public static CompletableFuture<List<? extends WorkspaceSymbol>> findSymbolsInWorkspace(
            @NotNull String symbolName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer) {
        final var allSymbolsFuture =
                sharedServer.query(server ->
                        server.getWorkspaceService().symbol(new WorkspaceSymbolParams(symbolName))
                );

        return allSymbolsFuture.thenApply(futureEither ->
                futureEither.thenApply(either -> {
                    if (either.isLeft()) {
                        // Case 1: Server sent the DEPRECATED type. Convert to the new type and filter.
                        return either.getLeft().stream()
                                .map(LspAnalyzerHelper::toWorkspaceSymbol)
                                .filter(symbol -> LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation()).startsWith(workspace))
                                .collect(Collectors.toList());
                    } else if (either.isRight()) {
                        // Case 2: Server sent the MODERN type. Just filter.
                        return either.getRight().stream()
                                .filter(symbol -> LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation()).startsWith(workspace))
                                .collect(Collectors.toList());
                    } else {
                        return new ArrayList<WorkspaceSymbol>();
                    }
                }).join()
        );
    }

    /**
     * Helper to convert a deprecated SymbolInformation object to the modern WorkspaceSymbol.
     */
    @NotNull
    @SuppressWarnings("deprecation")
    public static WorkspaceSymbol toWorkspaceSymbol(@NotNull SymbolInformation info) {
        var ws = new WorkspaceSymbol(info.getName(), info.getKind(), Either.forLeft(info.getLocation()));
        ws.setContainerName(info.getContainerName());
        return ws;
    }

    /**
     * Using the language-specific method full name resolution, will determine the name of the given method's "container" name and parent method name.
     *
     * @param methodFullName    the fully qualified method name. No special constructor handling is done.
     * @param resolveMethodName the method name cleaning function.
     * @return a qualified method record if successful, an empty result otherwise.
     */
    @NotNull
    public static Optional<QualifiedMethod> determineMethodName(
            @NotNull String methodFullName, @NotNull Function<String, String> resolveMethodName
    ) {
        final String cleanedName = resolveMethodName.apply(methodFullName);
        final int lastIndex = cleanedName.lastIndexOf('.');
        if (lastIndex != -1) {
            final String className = cleanedName.substring(0, lastIndex);
            final String methodName = cleanedName.substring(lastIndex + 1);
            return Optional.of(new QualifiedMethod(className, methodName));
        } else {
            return Optional.empty();
        }
    }

    public static boolean simpleOrFullMatch(@NotNull WorkspaceSymbol symbol, @NotNull String simpleOrFullName) {
        final String symbolFullName = symbol.getContainerName() + "." + symbol.getName();
        return symbol.getName().equals(simpleOrFullName) || symbolFullName.equals(simpleOrFullName);
    }

    /**
     * Finds a type (class, interface, enum) by its simple or fully qualified name within the workspace.
     * This gives a fuzzy match.
     *
     * @param containerName The exact, case-sensitive simple name of the package or type to find.
     * @return A CompletableFuture that will be completed with a list of matching symbols.
     */
    @NotNull
    public static CompletableFuture<List<WorkspaceSymbol>> findTypesInWorkspace(
            @NotNull String containerName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer
    ) {
        return findTypesInWorkspace(containerName, workspace, sharedServer, true);
    }

    /**
     * Finds a type (class, interface, enum) by its exact simple or fully qualified name within the workspace.
     *
     * @param containerName The exact, case-sensitive simple name of the package or type to find.
     * @param fuzzySearch   Whether to consider "close enough" matches or exact ones.
     * @return A CompletableFuture that will be completed with a list of matching symbols.
     */
    @NotNull
    public static CompletableFuture<List<WorkspaceSymbol>> findTypesInWorkspace(
            @NotNull String containerName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer,
            boolean fuzzySearch
    ) {
        if (fuzzySearch) {
            return typesByNameFuzzy(containerName, workspace, sharedServer)
                    .thenApply(symbols -> symbols.collect(Collectors.toList()));
        } else {
            return typesByName(containerName, workspace, sharedServer)
                    .thenApply(symbols ->
                            symbols.filter(symbol ->
                                    simpleOrFullMatch(symbol, containerName)
                            ).collect(Collectors.toList())
                    );
        }
    }

    @NotNull
    public static CompletableFuture<Stream<? extends WorkspaceSymbol>> typesByName(
            @NotNull String name, @NotNull String workspace, @NotNull LspServer sharedServer) {
        return typesByNameFuzzy(name, workspace, sharedServer)
                .thenApply(symbols ->
                        symbols.filter(symbol -> simpleOrFullMatch(symbol, name))
                );
    }

    @NotNull
    public static CompletableFuture<Stream<? extends WorkspaceSymbol>> typesByNameFuzzy(
            @NotNull String name, @NotNull String workspace, @NotNull LspServer sharedServer) {
        return LspAnalyzerHelper.findSymbolsInWorkspace(name, workspace, sharedServer).thenApply(symbols ->
                symbols.stream()
                        .filter(symbol -> TYPE_KINDS.contains(symbol.getKind()))
        );
    }

    /**
     * Searches for the method name using its parent container.
     *
     * @param containerName the parent container, this can be a type or package.
     * @param methodName    the method name to match.
     * @return all matching methods for the given parameters.
     */
    public static CompletableFuture<List<WorkspaceSymbol>> findMethodSymbol(
            @NotNull String containerName,
            @NotNull String methodName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer,
            @NotNull Function<String, String> resolveMethodName
    ) {
        return LspAnalyzerHelper.findTypesInWorkspace(containerName, workspace, sharedServer).thenCompose(classLocations -> {
            if (classLocations.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            return CompletableFuture.completedFuture(
                    findMethodSymbol(classLocations, methodName, sharedServer, resolveMethodName));
        });
    }

    private static List<WorkspaceSymbol> findMethodSymbol(
            @NotNull List<WorkspaceSymbol> classLocations,
            @NotNull String methodName,
            @NotNull LspServer sharedServer,
            @NotNull Function<String, String> resolveMethodName
    ) {
        return classLocations.stream().flatMap(classLocation -> {
            final String uriString = LspAnalyzerHelper.getUriStringFromLocation(classLocation.getLocation());
            final Path filePath = Paths.get(URI.create(uriString));
            return LspAnalyzerHelper.getSymbolsInFile(sharedServer, filePath).thenApply(fileSymbols ->
                    fileSymbols.stream().map(fileSymbolsEither -> {
                        if (fileSymbolsEither.isRight()) {
                            return LspAnalyzerHelper.findSymbolsInTree(
                                            Collections.singletonList(fileSymbolsEither.getRight()),
                                            methodName,
                                            METHOD_KINDS,
                                            resolveMethodName
                                    )
                                    .stream()
                                    .map(documentSymbol -> LspAnalyzerHelper.documentToWorkspaceSymbol(documentSymbol, uriString))
                                    .toList();
                        } else {
                            // Find the symbol and map it to a new Location object with a precise range
                            return new ArrayList<WorkspaceSymbol>();
                        }
                    }).flatMap(Collection::stream).toList()
            ).join().stream();
        }).toList();
    }

    /**
     * Gets all symbols that the server has indexed for this specific workspace.
     *
     * @return A CompletableFuture that will be completed with a list of all indexed symbols.
     */
    public static CompletableFuture<List<? extends WorkspaceSymbol>> getAllWorkspaceSymbols(@NotNull String workspace, @NotNull LspServer sharedServer) {
        // An empty query string "" tells the server to return all symbols. 
        // Relies on indexes, so shouldn't be too expensive
        return findSymbolsInWorkspace("*", workspace, sharedServer);
    }


}
