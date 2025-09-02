package io.github.jbellis.brokk.client;

import static dev.langchain4j.http.client.HttpMethod.POST;
import static dev.langchain4j.internal.InternalStreamingChatResponseHandlerUtils.withLoggingExceptions;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.Utils.isNotNullOrBlank;
import static dev.langchain4j.model.openai.OpenAiStreamingChatModel.handle;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.toOpenAiChatRequest;
import static java.time.Duration.ofSeconds;

import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.log.LoggingHttpClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingResponseBuilder;
import dev.langchain4j.model.openai.internal.Json;
import dev.langchain4j.model.openai.internal.RequestExecutor;
import dev.langchain4j.model.openai.internal.SyncOrAsyncOrStreaming;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.shared.StreamOptions;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OllamaStreamingChatModel implements StreamingChatModel {

    private final String modelName;
    private final URL baseUrl;
    private final HttpClient httpClient;
    private final boolean strictJsonSchema;
    private final boolean strictTools;

    @Nullable
    private String bearerToken;

    private OllamaStreamingChatModel(Builder builder) {
        if (builder.baseUrl != null) {
            this.baseUrl = builder.baseUrl;
        } else {
            throw new IllegalArgumentException("Base URL cannot be null");
        }

        if (builder.modelName != null && !builder.modelName.isBlank()) {
            // Strip away url suffix
            this.modelName = builder.modelName.replace(this.baseUrl + "/", "");
        } else {
            throw new IllegalArgumentException("Model name cannot be null or empty");
        }

        this.bearerToken = builder.bearerToken;
        this.strictJsonSchema = builder.strictJsonSchema;
        this.strictTools = builder.strictTools;

        HttpClientBuilder httpClientBuilder = JdkHttpClient.builder();
        HttpClient httpClient = httpClientBuilder
                .connectTimeout(getOrDefault(
                        getOrDefault(builder.connectTimeout, httpClientBuilder.connectTimeout()), ofSeconds(15)))
                .readTimeout(
                        getOrDefault(getOrDefault(builder.readTimeout, httpClientBuilder.readTimeout()), ofSeconds(60)))
                .build();

        if (builder.logRequests || builder.logResponses) {
            this.httpClient = new LoggingHttpClient(httpClient, builder.logRequests, builder.logResponses);
        } else {
            this.httpClient = httpClient;
        }
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        OpenAiChatRequestParameters parameters = chatRequest.parameters();

        ChatCompletionRequest openAiRequest = toOpenAiChatRequest(
                        chatRequest, parameters, this.strictTools, this.strictJsonSchema)
                .model(this.modelName) // MCP model name is just the ID, not the full location
                .stream(true)
                .streamOptions(StreamOptions.builder().includeUsage(true).build())
                .build();

        OpenAiStreamingResponseBuilder openAiResponseBuilder = new OpenAiStreamingResponseBuilder();

        final var streamBody = Json.toJson(
                ChatCompletionRequest.builder().from(openAiRequest).stream(true).build());
        // todo: okhttp3 is cumbersome to use in Java
        //        HttpRequest streamingHttpRequest = buildRequest(POST, "api/chat")
        //                .body(streamBody)
        //                .build();

        Request request = new Request.Builder()
                .url(baseUrl + "/api/chat")
                .post(RequestBody.create(MediaType.parse("application/json"), streamBody))
                .addHeader("Content-Type", "application/json")
                .build();

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                final var partialResponse = Json.fromJson(text, ChatCompletionResponse.class);
                openAiResponseBuilder.append(partialResponse);
                handle(partialResponse, handler);
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                super.onMessage(webSocket, bytes);
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                withLoggingExceptions(() -> handler.onError(t));
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                ChatResponse chatResponse = openAiResponseBuilder.build();
                //                try {
                //                    handler.onCompleteResponse(chatResponse);
                //                } catch (Exception e) {
                //                    withLoggingExceptions(() -> handler.onError(e));
                //                }
            }
        });

        //        httpClient.execute(streamingHttpRequest, new ServerSentEventListener() {
        //            @Override
        //            public void onEvent(ServerSentEvent event) {
        //                final var partialResponse = Json.fromJson(event.data(), ChatCompletionResponse.class);
        //                openAiResponseBuilder.append(partialResponse);
        //                handle(partialResponse, handler);
        //            }
        //
        //            @Override
        //            public void onError(Throwable throwable) {
        //                withLoggingExceptions(() -> handler.onError(throwable));
        //            }
        //
        //            @Override
        //            public void onClose() {
        //                ChatResponse chatResponse = openAiResponseBuilder.build();
        //                try {
        //                    handler.onCompleteResponse(chatResponse);
        //                } catch (Exception e) {
        //                    withLoggingExceptions(() -> handler.onError(e));
        //                }
        //            }
        //        });
    }

    public SyncOrAsyncOrStreaming<ChatCompletionResponse> chat(ChatCompletionRequest request) {
        final var nonStreamBody = Json.toJson(
                ChatCompletionRequest.builder().from(request).stream(false).build());
        HttpRequest httpRequest =
                buildRequest(POST, "api/chat").body(nonStreamBody).build();

        final var streamBody = Json.toJson(
                ChatCompletionRequest.builder().from(request).stream(true).build());
        HttpRequest streamingHttpRequest =
                buildRequest(POST, "api/chat").body(streamBody).build();

        return new RequestExecutor<>(httpClient, httpRequest, streamingHttpRequest, ChatCompletionResponse.class);
    }

    @Override
    public OpenAiChatRequestParameters defaultRequestParameters() {
        return OpenAiChatRequestParameters.builder()
                .modelName(baseUrl + "/" + this.modelName) // incorporate server details for unique identifier
                .build();
    }

    private HttpRequest.Builder buildRequest(HttpMethod method, String path) {
        final var builder = HttpRequest.builder()
                .addHeader("Content-Type", "application/json")
                .url(this.baseUrl.toString(), path)
                .method(method);

        if (bearerToken != null) {
            return builder.addHeader("Authorization", "Bearer " + bearerToken);
        } else {
            return builder;
        }
    }

    public static class Builder {

        private @Nullable String modelName;
        private @Nullable URL baseUrl;
        private @Nullable String bearerToken = null;
        private Duration readTimeout = Duration.ofSeconds(15);
        private Duration connectTimeout = Duration.ofSeconds(15);
        private boolean logRequests = false;
        private boolean logResponses = false;
        private boolean strictJsonSchema = false;
        private boolean strictTools = false;

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder baseUrl(URL baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder baseUrl(String baseUrl) throws URISyntaxException, MalformedURLException {
            this.baseUrl = new URI(baseUrl).toURL();
            return this;
        }

        public Builder bearerToken(@Nullable String bearerToken) {
            if (isNotNullOrBlank(bearerToken)) {
                this.bearerToken = bearerToken;
            }
            return this;
        }

        public Builder readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder logRequests(boolean logRequests) {
            this.logRequests = logRequests;
            return this;
        }

        public Builder logResponses(boolean logResponses) {
            this.logResponses = logResponses;
            return this;
        }

        public Builder strictJsonSchema(boolean strictJsonSchema) {
            this.strictJsonSchema = strictJsonSchema;
            return this;
        }

        public Builder strictTools(boolean strictTools) {
            this.strictTools = strictTools;
            return this;
        }

        public OllamaStreamingChatModel build() {
            return new OllamaStreamingChatModel(this);
        }
    }
}
