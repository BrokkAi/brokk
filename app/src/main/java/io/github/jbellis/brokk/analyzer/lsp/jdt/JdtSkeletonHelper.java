package io.github.jbellis.brokk.analyzer.lsp.jdt;

import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzerHelper;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class JdtSkeletonHelper {

    /**
     * Generates a skeleton source code view for a class at a given location.
     *
     * @param classLocation The location of the class to generate a skeleton for.
     * @return A CompletableFuture that will resolve with the skeleton string.
     */
    public static @NotNull CompletableFuture<Optional<String>> getSymbolSkeleton(
            @NotNull LspServer sharedServer,
            @NotNull Location classLocation,
            @NotNull String fullSource,
            boolean headerOnly
    ) {
        final Path filePath = Paths.get(URI.create(classLocation.getUri()));
        final Position position = classLocation.getRange().getStart();

        // Get the full symbol tree for the file
        return LspAnalyzerHelper.getSymbolsInFile(sharedServer, filePath)
                .thenApply(eithers -> eithers.stream()
                        .map(either -> {
                            if (either.isRight()) {
                                // Find the specific class symbol in the tree
                                return LspAnalyzerHelper.findSymbolInTree(
                                        Collections.singletonList(either.getRight()),
                                        position
                                ).map(symbol -> {
                                    StringBuilder sb = new StringBuilder();
                                    // Start the recursive build process
                                    buildSkeleton(symbol, sb, 0, fullSource, headerOnly);
                                    return sb.toString();
                                });

                            } else {
                                return Optional.<String>empty();
                            }
                        })
                        .flatMap(Optional::stream)
                        .findFirst());
    }

    /**
     * Builds the skeleton from the given symbol and source code. Note that JDT LSP does not expose modifiers, so we need
     * to fetch this via reading the source code.
     */
    private static void buildSkeleton(
            DocumentSymbol symbol,
            StringBuilder sb,
            int indent,
            String fullSource,
            boolean headerOnly
    ) {
        final String indentation = "  ".repeat(indent);
        final String snippet = LspAnalyzerHelper.getSourceFromMethodRange(fullSource, symbol.getRange());

        sb.append(indentation);

        switch (symbol.getKind()) {
            case Class, Interface, Enum -> {
                final int bodyStart = snippet.indexOf('{');
                if (bodyStart != -1) {
                    sb.append(snippet, 0, bodyStart + 1).append("\n");
                }

                final List<DocumentSymbol> children =
                        symbol.getChildren() != null ? symbol.getChildren() : Collections.emptyList();


                // Before processing all children, check if an implicit constructor is needed.
                if (symbol.getKind() == SymbolKind.Class && !headerOnly) {
                    final boolean hasExplicitConstructor = children.stream()
                            .anyMatch(c -> c.getKind() == SymbolKind.Constructor);

                    if (!hasExplicitConstructor) {
                        // Strip generics from class name for constructor, e.g., "MyClass<T>" -> "MyClass"
                        final String className = removeGeneric(symbol.getName());
                        sb.append("  ".repeat(indent + 1))
                                .append("public ").append(className).append("() {...}\n");
                    }
                }

                // Build skeletons for all explicit members.
                for (DocumentSymbol child : children) {
                    if (headerOnly && LspAnalyzerHelper.FIELD_KINDS.contains(child.getKind())) {
                        buildSkeleton(child, sb, indent + 1, fullSource, true);
                    } else if (!headerOnly) {
                        buildSkeleton(child, sb, indent + 1, fullSource, false);
                    }
                }

                // If this is headers only, then add the omission notice
                if (headerOnly) {
                    sb.append("  ".repeat(indent + 1)).append("[... methods not shown ...]\n");
                }

                sb.append(indentation).append("}\n");
            }
            case Method, Constructor, Function -> {
                final int bodyStart = snippet.indexOf('{');
                if (bodyStart != -1) {
                    sb.append(snippet, 0, bodyStart).append("{...}\n");
                } else {
                    sb.append(snippet).append("\n");
                }
            }
            case Field, Constant -> sb.append(snippet).append("\n");
            default -> sb.setLength(sb.length() - indentation.length());
        }
    }

    private static String removeGeneric(final String className) {
        int genericIndex = className.indexOf('<');
        if (genericIndex != -1) {
            return className.substring(0, genericIndex);
        } else {
            return className;
        }
    }

}
