package ai.brokk.analyzer;

/**
 * Marker interface for JVM-based languages (Java, Scala, etc.).
 *
 * <p>Used by UI code to enable JVM-specific settings (e.g., JDK selection) without instantiating analyzers.
 */
public interface JvmLanguage extends Language {}
