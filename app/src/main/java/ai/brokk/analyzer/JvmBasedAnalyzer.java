package ai.brokk.analyzer;

import org.jspecify.annotations.NullMarked;

/**
 * Marker interface for analyzers that target JVM-based languages (Java, Scala, etc.).
 * Used to conditionally enable JVM-specific features like JDK selection in the UI.
 */
@NullMarked
public interface JvmBasedAnalyzer extends IAnalyzer {}
