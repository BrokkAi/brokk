package dev.langchain4j.model.openai.internal;

import static dev.langchain4j.internal.Exceptions.illegalArgument;
import static dev.langchain4j.internal.JsonSchemaElementUtils.toMap;
import static dev.langchain4j.internal.Utils.isNullOrBlank;
import static dev.langchain4j.internal.Utils.isNullOrEmpty;
import static dev.langchain4j.model.chat.request.ResponseFormat.JSON;
import static dev.langchain4j.model.chat.request.ResponseFormatType.TEXT;
import static dev.langchain4j.model.openai.internal.chat.ResponseFormatType.JSON_OBJECT;
import static dev.langchain4j.model.openai.internal.chat.ResponseFormatType.JSON_SCHEMA;
import static dev.langchain4j.model.openai.internal.chat.ToolType.FUNCTION;
import static dev.langchain4j.model.output.FinishReason.CONTENT_FILTER;
import static dev.langchain4j.model.output.FinishReason.LENGTH;
import static dev.langchain4j.model.output.FinishReason.STOP;
import static dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage.InputTokensDetails;
import dev.langchain4j.model.openai.OpenAiTokenUsage.OutputTokensDetails;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.Function;
import dev.langchain4j.model.openai.internal.chat.FunctionCall;
import dev.langchain4j.model.openai.internal.chat.FunctionMessage;
import dev.langchain4j.model.openai.internal.chat.ImageDetail;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Tool;
import dev.langchain4j.model.openai.internal.chat.ToolCall;
import dev.langchain4j.model.openai.internal.chat.ToolChoiceMode;
import dev.langchain4j.model.openai.internal.chat.ToolMessage;
import dev.langchain4j.model.openai.internal.shared.CompletionTokensDetails;
import dev.langchain4j.model.openai.internal.shared.PromptTokensDetails;
import dev.langchain4j.model.openai.internal.shared.Usage;
import dev.langchain4j.model.output.FinishReason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("deprecation")
public class OpenAiUtils {

    public static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_USER_AGENT = "langchain4j-openai";

    public static List<Message> toOpenAiMessages(List<ChatMessage> messages) {
        return messages.stream().map(OpenAiUtils::toOpenAiMessage).collect(toList());
    }

    public static Message toOpenAiMessage(ChatMessage message) {
        if (message instanceof SystemMessage) {
            return dev.langchain4j.model.openai.internal.chat.SystemMessage.from(((SystemMessage) message).text());
        }

        if (message instanceof UserMessage userMessage) {

            if (userMessage.hasSingleText()) {
                return dev.langchain4j.model.openai.internal.chat.UserMessage.builder()
                        .content(userMessage.singleText())
                        .name(userMessage.name())
                        .build();
            } else {
                return dev.langchain4j.model.openai.internal.chat.UserMessage.builder()
                        .content(userMessage.contents().stream()
                                .map(OpenAiUtils::toOpenAiContent)
                                .collect(toList()))
                        .name(userMessage.name())
                        .build();
            }
        }

        if (message instanceof AiMessage aiMessage) {

            if (!aiMessage.hasToolExecutionRequests()) {
                return AssistantMessage.builder()
                        .content(aiMessage.text())
                        .reasoningContent(aiMessage.reasoningContent())
                        .thoughtSignature(aiMessage.thoughtSignature())
                        .build();
            }

            ToolExecutionRequest toolExecutionRequest =
                    aiMessage.toolExecutionRequests().get(0);
            if (toolExecutionRequest.id() == null) {
                FunctionCall functionCall = FunctionCall.builder()
                        .name(toolExecutionRequest.name())
                        .arguments(toolExecutionRequest.arguments())
                        .build();

                return AssistantMessage.builder()
                        .functionCall(functionCall)
                        .reasoningContent(aiMessage.reasoningContent())
                        .thoughtSignature(aiMessage.thoughtSignature())
                        .build();
            }

            List<ToolCall> toolCalls = aiMessage.toolExecutionRequests().stream()
                    .map(it -> ToolCall.builder()
                            .id(it.id())
                            .type(FUNCTION)
                            .function(FunctionCall.builder()
                                    .name(it.name())
                                    .arguments(isNullOrBlank(it.arguments()) ? "{}" : it.arguments())
                                    .build())
                            .build())
                    .collect(toList());

            return AssistantMessage.builder()
                    .content(aiMessage.text())
                    .reasoningContent(aiMessage.reasoningContent())
                    .thoughtSignature(aiMessage.thoughtSignature())
                    .toolCalls(toolCalls)
                    .build();
        }

        if (message instanceof ToolExecutionResultMessage toolExecutionResultMessage) {

            if (toolExecutionResultMessage.id() == null) {
                return FunctionMessage.from(toolExecutionResultMessage.toolName(), toolExecutionResultMessage.text());
            }

            return ToolMessage.from(
                    toolExecutionResultMessage.id(),
                    toolExecutionResultMessage.text(),
                    toolExecutionResultMessage.toolName());
        }

        throw illegalArgument("Unknown message type: " + message.type());
    }

    private static dev.langchain4j.model.openai.internal.chat.Content toOpenAiContent(Content content) {
        if (content instanceof TextContent) {
            return toOpenAiContent((TextContent) content);
        } else if (content instanceof ImageContent) {
            return toOpenAiContent((ImageContent) content);
        } else {
            throw illegalArgument("Unknown content type: " + content);
        }
    }

    private static dev.langchain4j.model.openai.internal.chat.Content toOpenAiContent(TextContent content) {
        return dev.langchain4j.model.openai.internal.chat.Content.builder()
                .type(ContentType.TEXT)
                .text(content.text())
                .build();
    }

    private static dev.langchain4j.model.openai.internal.chat.Content toOpenAiContent(ImageContent content) {
        return dev.langchain4j.model.openai.internal.chat.Content.builder()
                .type(ContentType.IMAGE_URL)
                .imageUrl(ImageUrl.builder()
                        .url(toUrl(content.image()))
                        .detail(toDetail(content.detailLevel()))
                        .build())
                .build();
    }

    private static String extractSubtype(String mimetype) {
        return mimetype.split("/")[1];
    }

    private static String toUrl(Image image) {
        if (image.url() != null) {
            return image.url().toString();
        }
        return format("data:%s;base64,%s", image.mimeType(), image.base64Data());
    }

    private static ImageDetail toDetail(ImageContent.DetailLevel detailLevel) {
        if (detailLevel == null) {
            return null;
        }
        return ImageDetail.valueOf(detailLevel.name());
    }

    public static List<Tool> toTools(Collection<ToolSpecification> toolSpecifications, boolean strict) {
        return toolSpecifications.stream()
                .map((ToolSpecification toolSpecification) -> toTool(toolSpecification, strict))
                .collect(toList());
    }

    private static Tool toTool(ToolSpecification toolSpecification, boolean strict) {
        Function.Builder functionBuilder = Function.builder()
                .name(toolSpecification.name())
                .description(toolSpecification.description())
                .parameters(toOpenAiParameters(toolSpecification.parameters(), strict));
        if (strict) {
            functionBuilder.strict(true);
        }
        Function function = functionBuilder.build();
        return Tool.from(function);
    }

    /** @deprecated Functions are deprecated by OpenAI, use {@link #toTools(Collection, boolean)} instead */
    @Deprecated
    public static List<Function> toFunctions(Collection<ToolSpecification> toolSpecifications) {
        return toolSpecifications.stream().map(OpenAiUtils::toFunction).collect(toList());
    }

    /** @deprecated Functions are deprecated by OpenAI, use {@link #toTool(ToolSpecification, boolean)} instead */
    @Deprecated
    private static Function toFunction(ToolSpecification toolSpecification) {
        return Function.builder()
                .name(toolSpecification.name())
                .description(toolSpecification.description())
                .parameters(toOpenAiParameters(toolSpecification.parameters(), false))
                .build();
    }

    private static Map<String, Object> toOpenAiParameters(JsonObjectSchema parameters, boolean strict) {
        if (parameters != null) {
            return toMap(parameters, strict);
        } else {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "object");
            map.put("properties", new HashMap<>());
            map.put("required", new ArrayList<>());
            if (strict) {
                // When strict, additionalProperties must be false:
                // See
                // https://platform.openai.com/docs/guides/structured-outputs/additionalproperties-false-must-always-be-set-in-objects?api-mode=chat#additionalproperties-false-must-always-be-set-in-objects
                map.put("additionalProperties", false);
            }
            return map;
        }
    }

    public static AiMessage aiMessageFrom(ChatCompletionResponse response) {
        AssistantMessage assistantMessage = response.choices().get(0).message();
        String text = assistantMessage.content();
        String reasoningContent = assistantMessage.reasoningContent();
        String thoughtSignature = assistantMessage.thoughtSignature();

        List<ToolCall> toolCalls = assistantMessage.toolCalls();
        if (!isNullOrEmpty(toolCalls)) {
            List<ToolExecutionRequest> toolExecutionRequests = toolCalls.stream()
                    .filter(toolCall -> toolCall.type() == FUNCTION)
                    .map(OpenAiUtils::toToolExecutionRequest)
                    .collect(toList());
            return isNullOrBlank(text)
                    ? AiMessage.from(text, reasoningContent, thoughtSignature, toolExecutionRequests)
                    : AiMessage.from(text, reasoningContent, thoughtSignature, toolExecutionRequests);
        }

        FunctionCall functionCall = assistantMessage.functionCall();
        if (functionCall != null) {
            ToolExecutionRequest toolExecutionRequest = ToolExecutionRequest.builder()
                    .name(functionCall.name())
                    .arguments(functionCall.arguments())
                    .build();
            return AiMessage.from(text, reasoningContent, thoughtSignature, singletonList(toolExecutionRequest));
        }

        return AiMessage.from(text, reasoningContent, thoughtSignature);
    }

    private static ToolExecutionRequest toToolExecutionRequest(ToolCall toolCall) {
        FunctionCall functionCall = toolCall.function();
        return ToolExecutionRequest.builder()
                .id(toolCall.id())
                .name(functionCall.name())
                .arguments(functionCall.arguments())
                .build();
    }

    public static OpenAiTokenUsage tokenUsageFrom(Usage openAiUsage) {
        if (openAiUsage == null) {
            return null;
        }

        PromptTokensDetails promptTokensDetails = openAiUsage.promptTokensDetails();
        InputTokensDetails inputTokensDetails = null;
        if (promptTokensDetails != null) {
            inputTokensDetails = InputTokensDetails.builder()
                    .cachedTokens(promptTokensDetails.cachedTokens())
                    .build();
        }

        CompletionTokensDetails completionTokensDetails = openAiUsage.completionTokensDetails();
        OutputTokensDetails outputTokensDetails = null;
        if (completionTokensDetails != null) {
            outputTokensDetails = OutputTokensDetails.builder()
                    .reasoningTokens(completionTokensDetails.reasoningTokens())
                    .build();
        }

        return OpenAiTokenUsage.builder()
                .inputTokenCount(openAiUsage.promptTokens())
                .inputTokensDetails(inputTokensDetails)
                .outputTokenCount(openAiUsage.completionTokens())
                .outputTokensDetails(outputTokensDetails)
                .totalTokenCount(openAiUsage.totalTokens())
                .build();
    }

    /**
     * Maps OpenAI's finish_reason string into our FinishReason enum.
     *
     * Propagation path summary:
     * - OpenAI HTTP responses (streaming and non-streaming) contain choices[].finish_reason (string) on a choice.
     * - The adapter/response builders (e.g. OpenAiStreamingResponseBuilder and the non-streaming adapter that
     *   converts ChatCompletionResponse -> ChatResponse / AiMessage) read that string from the first choice and
     *   call this method to map it to dev.langchain4j.model.output.FinishReason.
     * - The mapped FinishReason is then placed into OpenAiChatResponseMetadata (via its Builder.finishReason(...))
     *   and subsequently into ChatResponseMetadata.finishReason().
     *
     * Where finishReason can become null:
     * - If OpenAI omits finish_reason (null/absent), this method will return null and the metadata will have a null finishReason.
     * - Previously, unrecognized finish_reason strings were also mapped to null here, which caused valid but new/variant
     *   strings (for example from newer models or "opus"-style responses) to be dropped.
     *
     * Notes about Opus, gpt-5.2, flash-3:
     * - Structurally Opus responses use the same choices[].finish_reason field as other chat models, but some variants
     *   of models/platform behavior may use new or slightly different string values for finish_reason, or populate it
     *   later in streaming scenarios. That means an OpenAI-provided non-null finish_reason could still be mapped to null
     *   by this method if the string wasn't recognized.
     *
     * Recommendation implemented here:
     * - Keep null when OpenAI did not provide finish_reason (preserve semantic "unknown / not provided").
     * - For any non-null but unrecognized finish_reason string, map to FinishReason.OTHER instead of null.
     *   This preserves the fact that OpenAI provided a reason (so metadata.finishReason will be non-null), while
     *   still categorizing unknown values safely.
     *
     * Concrete places to change if different behavior is desired:
     * - OpenAiStreamingResponseBuilder (handle method): it reads choices[0].finish_reason and stores into an AtomicReference<FinishReason>.
     *   Ensure it calls finishReasonFrom(...) and updates metadata when streaming completes. If streaming populates finish_reason
     *   later, make sure the builder updates metadata after final chunk arrives.
     * - Non-streaming adapter (where ChatCompletionResponse -> ChatResponse/OpenAiChatResponseMetadata is converted):
     *   ensure the builder uses OpenAiUtils.finishReasonFrom(choice.finishReason()) and does not drop the mapped value.
     * - OpenAiChatResponseMetadata.Builder.finishReason(...) (and ChatResponseMetadata.Builder.finishReason(...)):
     *   Currently accept a nullable FinishReason; consider making it non-null by defaulting to FinishReason.OTHER where appropriate,
     *   or keep nullable but ensure callers pass OTHER for unrecognized non-null strings.
     *
     * Behavior change applied:
     * - Unrecognized non-null strings are now mapped to FinishReason.OTHER instead of returning null.
     *
     * Rationale:
     * - This change ensures that when OpenAI provides a finish_reason string (including novel values from Opus or other
     *   newer models), the ChatResponse metadata will carry a non-null FinishReason, making downstream consumers able to
     *   distinguish "OpenAI provided a reason but we don't classify it" (OTHER) from "OpenAI provided no reason" (null).
     */
    public static FinishReason finishReasonFrom(String openAiFinishReason) {
        if (openAiFinishReason == null) {
            // preserve semantic: OpenAI didn't provide a reason
            return null;
        }
        switch (openAiFinishReason) {
            case "stop":
                return STOP;
            case "length":
                return LENGTH;
            case "tool_calls":
            case "function_call":
                return TOOL_EXECUTION;
            case "content_filter":
                return CONTENT_FILTER;
            default:
                // If OpenAI provided a non-null but unknown finish_reason (e.g., new model-specific string),
                // return OTHER rather than null so metadata reflects that OpenAI supplied a reason.
                return FinishReason.OTHER;
        }
    }

    static dev.langchain4j.model.openai.internal.chat.ResponseFormat toOpenAiResponseFormat(
            ResponseFormat responseFormat, Boolean strict) {
        if (responseFormat == null || responseFormat.type() == TEXT) {
            return null;
        }

        JsonSchema jsonSchema = responseFormat.jsonSchema();
        if (jsonSchema == null) {
            return dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                    .type(JSON_OBJECT)
                    .build();
        } else {
            if (!(jsonSchema.rootElement() instanceof JsonObjectSchema)) {
                throw new IllegalArgumentException(
                        "For OpenAI, the root element of the JSON Schema must be a JsonObjectSchema, but it was: "
                                + jsonSchema.rootElement().getClass());
            }
            dev.langchain4j.model.openai.internal.chat.JsonSchema openAiJsonSchema =
                    dev.langchain4j.model.openai.internal.chat.JsonSchema.builder()
                            .name(jsonSchema.name())
                            .strict(strict)
                            .schema(toMap(jsonSchema.rootElement(), strict))
                            .build();
            return dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                    .type(JSON_SCHEMA)
                    .jsonSchema(openAiJsonSchema)
                    .build();
        }
    }

    public static ToolChoiceMode toOpenAiToolChoice(ToolChoice toolChoice) {
        if (toolChoice == null) {
            return null;
        }

        return switch (toolChoice) {
            case AUTO -> ToolChoiceMode.AUTO;
            case REQUIRED -> ToolChoiceMode.REQUIRED;
        };
    }

    public static ResponseFormat fromOpenAiResponseFormat(String responseFormat) {
        if ("json_object".equals(responseFormat)) {
            return JSON;
        } else {
            return null;
        }
    }

    public static ChatCompletionRequest.Builder toOpenAiChatRequest(
            ChatRequest chatRequest,
            OpenAiChatRequestParameters parameters,
            Boolean strictTools,
            Boolean strictJsonSchema) {
        return ChatCompletionRequest.builder()
                .messages(toOpenAiMessages(chatRequest.messages()))
                // common parameters
                .model(parameters.modelName())
                .temperature(parameters.temperature())
                .topP(parameters.topP())
                .frequencyPenalty(parameters.frequencyPenalty())
                .presencePenalty(parameters.presencePenalty())
                .maxTokens(parameters.maxOutputTokens())
                .stop(parameters.stopSequences())
                .tools(toTools(parameters.toolSpecifications(), strictTools))
                .toolChoice(toOpenAiToolChoice(parameters.toolChoice()))
                .responseFormat(toOpenAiResponseFormat(parameters.responseFormat(), strictJsonSchema))
                // OpenAI-specific parameters
                .maxCompletionTokens(parameters.maxCompletionTokens())
                .logitBias(parameters.logitBias())
                .parallelToolCalls(parameters.parallelToolCalls())
                .seed(parameters.seed())
                .user(parameters.user())
                .store(parameters.store())
                .metadata(parameters.metadata())
                .serviceTier(
                        parameters.serviceTier() != null
                                ? parameters.serviceTier().toApiString()
                                : null)
                .previousResponseId(parameters.previousResponseId())
                .reasoningEffort(parameters.reasoningEffort());
    }
}
