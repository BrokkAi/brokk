package io.github.jbellis.brokk.analyzer.lsp.domain;

import org.jetbrains.annotations.NotNull;

public record SymbolContext(@NotNull String containerFullName, @NotNull String methodName) {
}
