package io.github.jbellis.brokk.mcp;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.Utils.isNotNullOrBlank;
import static java.time.Duration.ofSeconds;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.log.LoggingHttpClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.jetbrains.annotations.Nullable;

public class McpStreamingChatModel implements StreamingChatModel {

    private final String modelName;
    private final URL baseUrl;
    private final HttpClient httpClient;

    @Nullable
    private String bearerToken;

    private McpStreamingChatModel(Builder builder) {
        if (isNotNullOrBlank(builder.modelName)) {
            this.modelName = builder.modelName;
        } else {
            throw new IllegalArgumentException("Model name cannot be null or empty");
        }
        if (builder.baseUrl != null) {
            this.baseUrl = builder.baseUrl;
        } else {
            throw new IllegalArgumentException("Base URL cannot be null");
        }
        this.bearerToken = builder.bearerToken;

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
        StreamingChatModel.super.doChat(chatRequest, handler);
    }

    @Override
    public OpenAiChatRequestParameters defaultRequestParameters() {
        return OpenAiChatRequestParameters.builder()
                .modelName(this.modelName)
                .build();
    }

    private HttpRequest.Builder buildRequest(HttpMethod method) {
        final var builder = HttpRequest.builder().url(this.baseUrl.toString()).method(method);

        if (bearerToken != null) {
            return builder.addHeader("Authorization", "Bearer " + bearerToken);
        } else {
            return builder;
        }
    }

    public static class Builder {

        private String modelName;
        private URL baseUrl;
        private String bearerToken = null;
        private Duration readTimeout = Duration.ofSeconds(15);
        private Duration connectTimeout = Duration.ofSeconds(15);
        private boolean logRequests = false;
        private boolean logResponses = false;

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

        public McpStreamingChatModel build() {
            return new McpStreamingChatModel(this);
        }
    }
}
