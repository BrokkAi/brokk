package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface LspAnalyzer extends IAnalyzer, AutoCloseable {

    Logger logger = LoggerFactory.getLogger(LspAnalyzer.class);

    @Override
    default boolean isCpg() {
        return false;
    }

    /** Transform method node fullName to a stable "resolved" name (e.g. removing lambda suffixes).
     */
    String resolveMethodName(@NotNull String methodName);

}
