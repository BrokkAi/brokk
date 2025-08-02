package dev.langchain4j.model.openai;

import static dev.langchain4j.internal.Utils.copy;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.Utils.quoted;
import static dev.langchain4j.model.chat.request.ResponseFormatType.JSON;
import static java.util.Arrays.asList;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonSchema;

public class OpenAiChatRequestParameters {

    public static final OpenAiChatRequestParameters EMPTY = OpenAiChatRequestParameters.builder().build();

    private final String modelName;
    private final Double temperature;
    private final Double topP;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Integer maxOutputTokens;
    private final List<String> stopSequences;
    private final List<ToolSpecification> toolSpecifications;
    private final ToolChoice toolChoice;
    private final ResponseFormat responseFormat;
    private final Integer maxCompletionTokens;
    private final Map<String, Integer> logitBias;
    private final Boolean parallelToolCalls;
    private final Integer seed;
    private final String user;
    private final Boolean store;
    private final Map<String, String> metadata;
    private final String serviceTier;
    private final String reasoningEffort;

    private OpenAiChatRequestParameters(Builder builder) {
        this.modelName = builder.modelName;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.stopSequences = copy(builder.stopSequences);
        this.toolSpecifications = copy(builder.toolSpecifications);
        this.toolChoice = builder.toolChoice;
        this.responseFormat = builder.responseFormat;
        this.maxCompletionTokens = builder.maxCompletionTokens;
        this.logitBias = copy(builder.logitBias);
        this.parallelToolCalls = builder.parallelToolCalls;
        this.seed = builder.seed;
        this.user = builder.user;
        this.store = builder.store;
        this.metadata = copy(builder.metadata);
        this.serviceTier = builder.serviceTier;
        this.reasoningEffort = builder.reasoningEffort;
    }

    public String modelName() {
        return modelName;
    }

    public Double temperature() {
        return temperature;
    }

    public Double topP() {
        return topP;
    }

    public Double frequencyPenalty() {
        return frequencyPenalty;
    }

    public Double presencePenalty() {
        return presencePenalty;
    }

    public Integer maxOutputTokens() {
        return maxOutputTokens;
    }

    public List<String> stopSequences() {
        return stopSequences;
    }

    public List<ToolSpecification> toolSpecifications() {
        return toolSpecifications;
    }

    public ToolChoice toolChoice() {
        return toolChoice;
    }

    public ResponseFormat responseFormat() {
        return responseFormat;
    }

    public Integer maxCompletionTokens() {
        return maxCompletionTokens;
    }

    public Map<String, Integer> logitBias() {
        return logitBias;
    }

    public Boolean parallelToolCalls() {
        return parallelToolCalls;
    }

    public Integer seed() {
        return seed;
    }

    public String user() {
        return user;
    }

    public Boolean store() {
        return store;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public String serviceTier() {
        return serviceTier;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public OpenAiChatRequestParameters overrideWith(OpenAiChatRequestParameters that) {
        return OpenAiChatRequestParameters.builder()
                .overrideWith(this)
                .overrideWith(that)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OpenAiChatRequestParameters that = (OpenAiChatRequestParameters) o;
        return Objects.equals(modelName, that.modelName)
                && Objects.equals(temperature, that.temperature)
                && Objects.equals(topP, that.topP)
                && Objects.equals(frequencyPenalty, that.frequencyPenalty)
                && Objects.equals(presencePenalty, that.presencePenalty)
                && Objects.equals(maxOutputTokens, that.maxOutputTokens)
                && Objects.equals(stopSequences, that.stopSequences)
                && Objects.equals(toolSpecifications, that.toolSpecifications)
                && Objects.equals(toolChoice, that.toolChoice)
                && Objects.equals(responseFormat, that.responseFormat)
                && Objects.equals(maxCompletionTokens, that.maxCompletionTokens)
                && Objects.equals(logitBias, that.logitBias)
                && Objects.equals(parallelToolCalls, that.parallelToolCalls)
                && Objects.equals(seed, that.seed)
                && Objects.equals(user, that.user)
                && Objects.equals(store, that.store)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(serviceTier, that.serviceTier)
                && Objects.equals(reasoningEffort, that.reasoningEffort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                modelName,
                temperature,
                topP,
                frequencyPenalty,
                presencePenalty,
                maxOutputTokens,
                stopSequences,
                toolSpecifications,
                toolChoice,
                responseFormat,
                maxCompletionTokens,
                logitBias,
                parallelToolCalls,
                seed,
                user,
                store,
                metadata,
                serviceTier,
                reasoningEffort
        );
    }

    @Override
    public String toString() {
        return "OpenAiChatRequestParameters{" +
                "modelName=" + quoted(modelName) +
                ", temperature=" + temperature() +
                ", topP=" + topP() +
                ", frequencyPenalty=" + frequencyPenalty() +
                ", presencePenalty=" + presencePenalty() +
                ", maxOutputTokens=" + maxOutputTokens() +
                ", stopSequences=" + stopSequences() +
                ", toolSpecifications=" + toolSpecifications() +
                ", toolChoice=" + toolChoice() +
                ", responseFormat=" + responseFormat() +
                ", maxCompletionTokens=" + maxCompletionTokens +
                ", logitBias=" + logitBias +
                ", parallelToolCalls=" + parallelToolCalls +
                ", seed=" + seed +
                ", user=" + quoted(user) +
                ", store=" + store +
                ", metadata=" + metadata +
                ", serviceTier=" + quoted(serviceTier) +
                ", reasoningEffort=" + quoted(reasoningEffort) +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String modelName;
        private Double temperature;
        private Double topP;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Integer maxOutputTokens;
        private List<String> stopSequences;
        private List<ToolSpecification> toolSpecifications;
        private ToolChoice toolChoice;
        private ResponseFormat responseFormat;
        private Integer maxCompletionTokens;
        private Map<String, Integer> logitBias;
        private Boolean parallelToolCalls;
        private Integer seed;
        private String user;
        private Boolean store;
        private Map<String, String> metadata;
        private String serviceTier;
        private String reasoningEffort;

        public Builder overrideWith(OpenAiChatRequestParameters parameters) {
            modelName(getOrDefault(parameters.modelName(), modelName));
            temperature(getOrDefault(parameters.temperature(), temperature));
            topP(getOrDefault(parameters.topP(), topP));
            frequencyPenalty(getOrDefault(parameters.frequencyPenalty(), frequencyPenalty));
            presencePenalty(getOrDefault(parameters.presencePenalty(), presencePenalty));
            maxOutputTokens(getOrDefault(parameters.maxOutputTokens(), maxOutputTokens));
            stopSequences(getOrDefault(parameters.stopSequences(), stopSequences));
            toolSpecifications(getOrDefault(parameters.toolSpecifications(), toolSpecifications));
            toolChoice(getOrDefault(parameters.toolChoice(), toolChoice));
            responseFormat(getOrDefault(parameters.responseFormat(), responseFormat));
            maxCompletionTokens(getOrDefault(parameters.maxCompletionTokens(), maxCompletionTokens));
            logitBias(getOrDefault(parameters.logitBias(), logitBias));
            parallelToolCalls(getOrDefault(parameters.parallelToolCalls(), parallelToolCalls));
            seed(getOrDefault(parameters.seed(), seed));
            user(getOrDefault(parameters.user(), user));
            store(getOrDefault(parameters.store(), store));
            metadata(getOrDefault(parameters.metadata(), metadata));
            serviceTier(getOrDefault(parameters.serviceTier(), serviceTier));
            reasoningEffort(getOrDefault(parameters.reasoningEffort(), reasoningEffort));
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        /**
         * @see #stopSequences(String...)
         */
        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences;
            return this;
        }

        /**
         * @see #stopSequences(List)
         */
        public Builder stopSequences(String... stopSequences) {
            return stopSequences(asList(stopSequences));
        }

        /**
         * @see #toolSpecifications(ToolSpecification...)
         */
        public Builder toolSpecifications(List<ToolSpecification> toolSpecifications) {
            this.toolSpecifications = toolSpecifications;
            return this;
        }

        /**
         * @see #toolSpecifications(List)
         */
        public Builder toolSpecifications(ToolSpecification... toolSpecifications) {
            return toolSpecifications(asList(toolSpecifications));
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        /**
         * @see #responseFormat(JsonSchema)
         */
        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        /**
         * @see #responseFormat(ResponseFormat)
         */
        public Builder responseFormat(JsonSchema jsonSchema) {
            if (jsonSchema != null) {
                ResponseFormat responseFormat = ResponseFormat.builder()
                        .type(JSON)
                        .jsonSchema(jsonSchema)
                        .build();
                return responseFormat(responseFormat);
            }
            return this;
        }

        public Builder modelName(OpenAiChatModelName modelName) {
            return modelName(modelName.toString());
        }

        public Builder maxCompletionTokens(Integer maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        public Builder logitBias(Map<String, Integer> logitBias) {
            this.logitBias = logitBias;
            return this;
        }

        public Builder parallelToolCalls(Boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
            return this;
        }

        public Builder seed(Integer seed) {
            this.seed = seed;
            return this;
        }

        public Builder user(String user) {
            this.user = user;
            return this;
        }

        public Builder store(Boolean store) {
            this.store = store;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder serviceTier(String serviceTier) {
            this.serviceTier = serviceTier;
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public OpenAiChatRequestParameters build() {
            return new OpenAiChatRequestParameters(this);
        }
    }
}
