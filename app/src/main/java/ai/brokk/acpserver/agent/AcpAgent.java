package ai.brokk.acpserver.agent;

import ai.brokk.acpserver.spec.AcpSchema.CancelRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextAddFilesResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextDropResponse;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetRequest;
import ai.brokk.acpserver.spec.AcpSchema.ContextGetResponse;
import ai.brokk.acpserver.spec.AcpSchema.InitializeRequest;
import ai.brokk.acpserver.spec.AcpSchema.InitializeResponse;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.ModelsListResponse;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionRequest;
import ai.brokk.acpserver.spec.AcpSchema.NewSessionResponse;
import ai.brokk.acpserver.spec.AcpSchema.PromptRequest;
import ai.brokk.acpserver.spec.AcpSchema.PromptResponse;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListRequest;
import ai.brokk.acpserver.spec.AcpSchema.SessionsListResponse;
import ai.brokk.acpserver.transport.AcpTransport;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Entry point for building ACP agents.
 */
public final class AcpAgent {

    private AcpAgent() {}

    public static SyncAgentBuilder sync(AcpTransport transport) {
        return new SyncAgentBuilder(transport);
    }

    public static class SyncAgentBuilder {
        private final AcpTransport transport;
        private @Nullable Function<InitializeRequest, InitializeResponse> initializeHandler;
        private @Nullable Function<NewSessionRequest, NewSessionResponse> newSessionHandler;
        private @Nullable BiFunction<PromptRequest, SyncPromptContext, PromptResponse> promptHandler;
        private @Nullable Function<ModelsListRequest, ModelsListResponse> modelsListHandler;
        private @Nullable Function<ContextGetRequest, ContextGetResponse> contextGetHandler;
        private @Nullable Function<ContextAddFilesRequest, ContextAddFilesResponse> contextAddFilesHandler;
        private @Nullable Function<ContextDropRequest, ContextDropResponse> contextDropHandler;
        private @Nullable Function<SessionsListRequest, SessionsListResponse> sessionsListHandler;
        private @Nullable Consumer<CancelRequest> cancelHandler;

        SyncAgentBuilder(AcpTransport transport) {
            this.transport = transport;
        }

        public SyncAgentBuilder initializeHandler(Function<InitializeRequest, InitializeResponse> handler) {
            this.initializeHandler = handler;
            return this;
        }

        public SyncAgentBuilder newSessionHandler(Function<NewSessionRequest, NewSessionResponse> handler) {
            this.newSessionHandler = handler;
            return this;
        }

        public SyncAgentBuilder promptHandler(BiFunction<PromptRequest, SyncPromptContext, PromptResponse> handler) {
            this.promptHandler = handler;
            return this;
        }

        public SyncAgentBuilder modelsListHandler(Function<ModelsListRequest, ModelsListResponse> handler) {
            this.modelsListHandler = handler;
            return this;
        }

        public SyncAgentBuilder contextGetHandler(Function<ContextGetRequest, ContextGetResponse> handler) {
            this.contextGetHandler = handler;
            return this;
        }

        public SyncAgentBuilder contextAddFilesHandler(
                Function<ContextAddFilesRequest, ContextAddFilesResponse> handler) {
            this.contextAddFilesHandler = handler;
            return this;
        }

        public SyncAgentBuilder contextDropHandler(Function<ContextDropRequest, ContextDropResponse> handler) {
            this.contextDropHandler = handler;
            return this;
        }

        public SyncAgentBuilder sessionsListHandler(Function<SessionsListRequest, SessionsListResponse> handler) {
            this.sessionsListHandler = handler;
            return this;
        }

        public SyncAgentBuilder cancelHandler(Consumer<CancelRequest> handler) {
            this.cancelHandler = handler;
            return this;
        }

        public AcpSyncAgent build() {
            if (initializeHandler == null) throw new IllegalStateException("initializeHandler is required");
            if (newSessionHandler == null) throw new IllegalStateException("newSessionHandler is required");
            if (promptHandler == null) throw new IllegalStateException("promptHandler is required");
            if (modelsListHandler == null) throw new IllegalStateException("modelsListHandler is required");
            if (contextGetHandler == null) throw new IllegalStateException("contextGetHandler is required");
            if (contextAddFilesHandler == null) throw new IllegalStateException("contextAddFilesHandler is required");
            if (contextDropHandler == null) throw new IllegalStateException("contextDropHandler is required");
            if (sessionsListHandler == null) throw new IllegalStateException("sessionsListHandler is required");
            return new AcpSyncAgent(
                    transport,
                    initializeHandler,
                    newSessionHandler,
                    promptHandler,
                    modelsListHandler,
                    contextGetHandler,
                    contextAddFilesHandler,
                    contextDropHandler,
                    sessionsListHandler,
                    cancelHandler != null ? cancelHandler : req -> {});
        }
    }
}
