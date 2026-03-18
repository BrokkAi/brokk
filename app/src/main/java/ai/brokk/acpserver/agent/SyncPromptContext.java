package ai.brokk.acpserver.agent;

import ai.brokk.acpserver.spec.AcpSchema.AgentMessageChunk;
import ai.brokk.acpserver.spec.AcpSchema.AgentThought;
import ai.brokk.acpserver.spec.AcpSchema.SessionUpdateNotification;
import ai.brokk.acpserver.spec.AcpSchema.TextContent;
import ai.brokk.acpserver.transport.AcpTransport;

/**
 * Context provided to prompt handlers for sending streaming updates.
 * <p>
 * This allows agents to send incremental messages and thoughts back to the client
 * during prompt processing, before the final response is returned.
 */
public class SyncPromptContext {

    private final String sessionId;
    private final AcpTransport transport;

    public SyncPromptContext(String sessionId, AcpTransport transport) {
        this.sessionId = sessionId;
        this.transport = transport;
    }

    /**
     * Sends a text message chunk to the client.
     *
     * @param text the message text to send
     */
    public void sendMessage(String text) {
        var notification = new SessionUpdateNotification(sessionId, new AgentMessageChunk(new TextContent(text)));
        transport.sendNotification("session/update", notification);
    }

    /**
     * Sends an agent thought to the client.
     *
     * @param thought the thought text to send
     */
    public void sendThought(String thought) {
        var notification = new SessionUpdateNotification(sessionId, new AgentThought(thought));
        transport.sendNotification("session/update", notification);
    }

    /**
     * Returns the session ID for this context.
     */
    public String sessionId() {
        return sessionId;
    }
}
