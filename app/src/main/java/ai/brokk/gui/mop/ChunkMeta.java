package ai.brokk.gui.mop;

import ai.brokk.LlmOutputMeta;

/**
 * Metadata describing properties of an LLM output chunk.
 * Used by MarkdownOutputPanel and passed through JCEFWebViewHost to JCEFBridge.
 */
public record ChunkMeta(boolean isNewMessage, boolean isReasoning, boolean isTerminal) {
    public static ChunkMeta fromLlmOutputMeta(LlmOutputMeta meta) {
        return new ChunkMeta(meta.isNewMessage(), meta.isReasoning(), meta.isTerminal());
    }

    public static ChunkMeta fromLlmOutputMeta(LlmOutputMeta meta, boolean isNewMessage) {
        return new ChunkMeta(isNewMessage, meta.isReasoning(), meta.isTerminal());
    }
}
