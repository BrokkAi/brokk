package dev.langchain4j.model.openai.internal;

import java.time.Duration;
import java.util.Map;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.spi.OpenAiClientBuilderFactory;
import dev.langchain4j.model.openai.internal.spi.ServiceHelper;

public abstract class OpenAiClient {

    public abstract SyncOrAsyncOrStreaming<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request);

    @SuppressWarnings("rawtypes")
    public static Builder builder() {
        for (OpenAiClientBuilderFactory factory : ServiceHelper.loadFactories(OpenAiClientBuilderFactory.class)) {
            return factory.get();
        }
        // fallback to the default
        return DefaultOpenAiClient.builder();
    }

    @SuppressWarnings("unchecked")
    public abstract static class Builder<T extends OpenAiClient, B extends Builder<T, B>> {

        public HttpClientBuilder httpClientBuilder;
        public String baseUrl;
        public String organizationId;
        public String projectId;
        public String apiKey;
        public Duration connectTimeout;
        public Duration readTimeout;
        public String userAgent;
        public boolean logRequests;
        public boolean logResponses;
        public Map<String, String> customHeaders;

        public abstract T build();

        public B httpClientBuilder(HttpClientBuilder httpClientBuilder) {
            this.httpClientBuilder = httpClientBuilder;
            return (B) this;
        }

        /**
         * @param baseUrl Base URL of OpenAI API. For example: "https://api.openai.com/v1/"
         * @return builder
         */
        public B baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return (B) this;
        }

        /**
         * @param organizationId The OpenAI Organization ID.
         *                       More info <a href="https://platform.openai.com/docs/api-reference/organizations-and-projects-optional">here</a>.
         * @return builder
         */
        public B organizationId(String organizationId) {
            this.organizationId = organizationId;
            return (B) this;
        }

        /**
         * @param projectId The OpenAI Project ID.
         *                  More info <a href="https://platform.openai.com/docs/api-reference/organizations-and-projects-optional">here</a>.
         * @return builder
         */
        public B projectId(String projectId) {
            this.projectId = projectId;
            return (B) this;
        }

        /**
         * @param apiKey OpenAI API key.
         *               Will be injected in HTTP headers like this: "Authorization: Bearer ${apiKey}"
         * @return builder
         */
        public B apiKey(String apiKey) {
            this.apiKey = apiKey;
            return (B) this;
        }

        public B connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return (B) this;
        }

        public B readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return (B) this;
        }

        public B userAgent(String userAgent) {
            this.userAgent = userAgent;
            return (B) this;
        }

        public B logRequests(Boolean logRequests) {
            if (logRequests == null) {
                logRequests = false;
            }
            this.logRequests = logRequests;
            return (B) this;
        }

        public B logResponses(Boolean logResponses) {
            if (logResponses == null) {
                logResponses = false;
            }
            this.logResponses = logResponses;
            return (B) this;
        }

        /**
         * Custom headers to be added to each HTTP request.
         *
         * @param customHeaders a map of headers
         * @return builder
         */
        public B customHeaders(Map<String, String> customHeaders) {
            this.customHeaders = customHeaders;
            return (B) this;
        }
    }
}
