package ai.brokk.analyzer;

/**
 * Marker interface for analyzers that target JVM-based languages (Java, Scala, etc.).
 * Used to conditionally enable JVM-specific features like JDK selection in the UI.
 */
public interface JvmBasedAnalyzer extends IAnalyzer {}
