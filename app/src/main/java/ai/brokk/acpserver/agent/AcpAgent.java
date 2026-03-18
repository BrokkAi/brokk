package ai.brokk.acpserver.agent;

import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.transport.AcpTransport;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Entry point for building ACP agents.
 * <p>
 * Usage:
 * <pre>{@code
 * AcpSyncAgent agent = AcpAgent.sync(transport)
 *     .initializeHandler(req -> InitializeResponse.ok())
 *     .newSessionHandler(req -> new NewSessionResponse(UUID.randomUUID().toString(), null, null))
 *     .promptHandler((req, ctx) -> {
 *         ctx.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     })
 *     .build();
 *
 * agent.run();
 * }</pre>
 */
public final class AcpAgent {

    private AcpAgent() {}

    /**
     * Creates a builder for a synchronous ACP agent.
     *
     * @param transport the transport to use for communication
     * @return a new sync agent builder
     */
    public static SyncAgentBuilder sync(AcpTransport transport) {
        return new SyncAgentBuilder(transport);
    }

    /**
     * Builder for synchronous ACP agents.
     */
    public static class SyncAgentBuilder {
        private final AcpTransport transport;
        private @Nullable Function<InitializeRequest, InitializeResponse> initializeHandler;
        private @Nullable Function<NewSessionRequest, NewSessionResponse> newSessionHandler;
        private @Nullable BiFunction<PromptRequest, SyncPromptContext, PromptResponse> promptHandler;

        SyncAgentBuilder(AcpTransport transport) {
            this.transport = transport;
        }

        /**
         * Sets the handler for initialize requests.
         */
        public SyncAgentBuilder initializeHandler(Function<InitializeRequest, InitializeResponse> handler) {
            this.initializeHandler = handler;
            return this;
        }

        /**
         * Sets the handler for new session requests.
         */
        public SyncAgentBuilder newSessionHandler(Function<NewSessionRequest, NewSessionResponse> handler) {
            this.newSessionHandler = handler;
            return this;
        }

        /**
         * Sets the handler for prompt requests.
         */
        public SyncAgentBuilder promptHandler(BiFunction<PromptRequest, SyncPromptContext, PromptResponse> handler) {
            this.promptHandler = handler;
            return this;
        }

        /**
         * Builds the synchronous agent.
         *
         * @throws IllegalStateException if required handlers are not set
         */
        public AcpSyncAgent build() {
            if (initializeHandler == null) {
                throw new IllegalStateException("initializeHandler is required");
            }
            if (newSessionHandler == null) {
                throw new IllegalStateException("newSessionHandler is required");
            }
            if (promptHandler == null) {
                throw new IllegalStateException("promptHandler is required");
            }
            return new AcpSyncAgent(transport, initializeHandler, newSessionHandler, promptHandler);
        }
    }
}
