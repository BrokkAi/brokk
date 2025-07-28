package io.github.jbellis.brokk.analyzer.lsp.domain;

import org.jetbrains.annotations.NotNull;

public record QualifiedMethod(@NotNull String containerFullName, @NotNull String methodName) {
}
