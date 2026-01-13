package ai.brokk.gui.mop;

/**
 * Metadata describing properties of an LLM output chunk.
 * Used by MarkdownOutputPanel and passed through MOPWebViewHost to MOPBridge.
 */
public record ChunkMeta(boolean isNewMessage, boolean isReasoning, boolean isTerminal) {}
