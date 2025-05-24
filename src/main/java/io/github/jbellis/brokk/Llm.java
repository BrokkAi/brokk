package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The main orchestrator for sending requests to an LLM, possibly with tools, collecting
 * streaming responses, etc.
 */
public class Llm {
    private static final Logger logger = LogManager.getLogger(Llm.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Base directory where LLM interaction history logs are stored. */
    public static final String HISTORY_DIR_NAME = "llm-history";

    private final IConsoleIO io;
    private final Path sessionHistoryDir; // Directory for this specific LLM session's history files
    final IContextManager contextManager;
    private final int MAX_ATTEMPTS = 8; // Keep retry logic for now
    private final StreamingChatLanguageModel model;
    private final boolean allowPartialResponses;
    private final boolean tagRetain;

    public Llm(StreamingChatLanguageModel model, String taskDescription, IContextManager contextManager, boolean allowPartialResponses, boolean tagRetain) {
        this.model = model;
        this.contextManager = contextManager;
        this.io = contextManager.getIo();
        this.allowPartialResponses = allowPartialResponses;
        this.tagRetain = tagRetain;
        var historyBaseDir = getHistoryBaseDir(contextManager.getProject().getRoot());

        // Create session directory name for this specific LLM interaction
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        var sessionDesc = LogDescription.getShortDescription(taskDescription);
        var sessionDirName = String.format("%s %s", timestamp, sessionDesc);
        this.sessionHistoryDir = historyBaseDir.resolve(sessionDirName);

        // Create the specific directory for this session
        try {
            Files.createDirectories(this.sessionHistoryDir);
        } catch (IOException e) {
            logger.error("Failed to create session history directory {}", this.sessionHistoryDir, e);
            // sessionHistoryDir might be null or unusable, logRequest checks for null
        }
    }

    /**
     * Returns the base directory where all LLM session histories are stored for a project.
     * @param projectRoot The root path of the project.
     * @return The Path object representing the base history directory.
     */
    public static Path getHistoryBaseDir(Path projectRoot) {
        return projectRoot.resolve(".brokk").resolve(HISTORY_DIR_NAME);
    }

    /**
     * Actually performs one streaming call to the LLM, returning once the response
     * is done or there's an error. If 'echo' is true, partial tokens go to console.
     */
    private StreamingResult doSingleStreamingCall(ChatRequest request, boolean echo) throws InterruptedException {
        StreamingResult result;
        try {
            result = doSingleStreamingCallInternal(request, echo);
        } catch (InterruptedException e) {
            logRequest(model, request, null);
            throw e;
        }
        logRequest(model, request, result);
        return result;
    }

    private StreamingResult doSingleStreamingCallInternal(ChatRequest request, boolean echo) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        // latch for awaiting the complete response
        var latch = new CountDownLatch(1);
        var cancelled = new AtomicBoolean(false);
        var lock = new ReentrantLock(); // Used by ifNotCancelled

        // Variables to store results from callbacks
        var accumulatedTextBuilder = new StringBuilder();
        var completedChatResponse = new AtomicReference<ChatResponse>();
        var errorRef = new AtomicReference<Throwable>();

        Consumer<Runnable> ifNotCancelled = (r) -> {
            lock.lock();
            try {
                if (!cancelled.get()) {
                    r.run();
                }
            } finally {
                lock.unlock();
            }
        };

        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                ifNotCancelled.accept(() -> {
                    accumulatedTextBuilder.append(token);
                    if (echo) {
                        io.llmOutput(token, ChatMessageType.AI);
                    }
                });
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                ifNotCancelled.accept(() -> {
                    if (response == null) {
                        if (completedChatResponse.get() != null) {
                            logger.debug("Got a null response from LC4J after a successful one!?");
                            // ignore the null response
                            return;
                        }
                        // I think this isn't supposed to happen, but seeing it when litellm throws back a 400.
                        // Fake an exception so the caller can treat it like other errors
                        errorRef.set(new HttpException(400, "BadRequestError (no further information, the response was null; check litellm logs)"));
                    } else {
                        completedChatResponse.set(response);
                        String tokens = response.tokenUsage() == null ? "null token usage!?" : formatTokensUsage(response);
                        logger.debug("Request complete ({}) with {}", response.finishReason(), tokens);
                    }
                    latch.countDown();
                });
            }

            @Override
            public void onError(Throwable th) {
                ifNotCancelled.accept(() -> {
                    io.toolErrorRaw("LLM error: " + th.getMessage()); // Immediate feedback for user
                    errorRef.set(th);
                    latch.countDown();
                });
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            lock.lock();
            cancelled.set(true); // Ensure callback stops echoing
            lock.unlock();
            throw e; // Propagate interruption
        }

        // At this point, latch has been counted down and we have a result or an error
        var error = errorRef.get();

        if (error != null) {
            // If no partial text, just return null response
            var partialText = accumulatedTextBuilder.toString();
            if (partialText.isEmpty()) {
                return new StreamingResult(null, error);
            }

            // Construct a ChatResponse from accumulated partial text
            var aiMessage = AiMessage.from(partialText);
            var responseFromPartialData = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .finishReason(FinishReason.OTHER)
                    .build();

            logger.debug("LLM call resulted in error: {}. Partial text captured: {} chars", error.getMessage(), partialText.length());
            return new StreamingResult(responseFromPartialData, null, error);
        }

        // Happy path: successful completion, no errors
        var response = completedChatResponse.get(); // Will be null if an error occurred or onComplete got null
        assert response != null : "If no error, completedChatResponse must be set by onCompleteResponse";
        if (echo) {
            io.llmOutput("\n", ChatMessageType.AI);
        }
        return new StreamingResult(response, null);
    }

    private static @NotNull String formatTokensUsage(ChatResponse response) {
        var tu = (OpenAiTokenUsage) response.tokenUsage();
        var template = "token usage: %,d input (%s cached), %,d output (%s reasoning)";
        return template.formatted(tu.inputTokenCount(),
                                  (tu.inputTokensDetails() == null) ? "?" : "%,d".formatted(tu.inputTokensDetails().cachedTokens()),
                                  tu.outputTokenCount(),
                                  (tu.outputTokensDetails() == null) ? "?" : "%,d".formatted(tu.outputTokensDetails().reasoningTokens()));
    }

    /**
     * Sends a user query to the LLM with streaming. Tools are not used.
     * Writes to conversation history. Optionally echoes partial tokens to the console.
     *
     * @param messages The messages to send
     * @param echo     Whether to echo LLM responses to the console as they stream
     * @return The final response from the LLM as a record containing ChatResponse, errors, etc.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages, boolean echo) throws InterruptedException {
        return sendMessageWithRetry(messages, List.of(), ToolChoice.AUTO, echo, MAX_ATTEMPTS);
    }

    /**
     * Sends messages to a given model, no tools, no streaming echo.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages) throws InterruptedException {
        return sendRequest(messages, false);
    }

    /**
     * Sends messages to a model with possible tools and a chosen tool usage policy.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages,
                                       List<ToolSpecification> tools,
                                       ToolChoice toolChoice,
                                       boolean echo) throws InterruptedException
    {
        var result = sendMessageWithRetry(messages, tools, toolChoice, echo, MAX_ATTEMPTS);
        var cr = result.chatResponse();

        // poor man's ToolChoice.REQUIRED (not supported by langchain4j for Anthropic)
        // Also needed for our emulation if it returns a response without a tool call
        while (result.error == null
                && !tools.isEmpty()
                && !cr.aiMessage().hasToolExecutionRequests()
                && toolChoice == ToolChoice.REQUIRED)
        {
            io.systemOutput("Enforcing tool selection");

            var extraMessages = new ArrayList<>(messages);
            extraMessages.add(cr.aiMessage());
            extraMessages.add(new UserMessage("At least one tool execution request is REQUIRED. Please call a tool."));

            result = sendMessageWithRetry(extraMessages, tools, toolChoice, echo, MAX_ATTEMPTS);
            cr = result.chatResponse();
        }

        return result;
    }

    /**
     * Retries a request up to maxAttempts times on connectivity or empty-result errors,
     * using exponential backoff. Responsible for writeToHistory.
     */
    private StreamingResult sendMessageWithRetry(List<ChatMessage> rawMessages,
                                                 List<ToolSpecification> tools,
                                                 ToolChoice toolChoice,
                                                 boolean echo,
                                                 int maxAttempts) throws InterruptedException
    {
        Throwable lastError = null;
        int attempt = 0;
        var messages = Messages.forLlm(rawMessages);

        StreamingResult response = null;
        while (attempt++ < maxAttempts) {
            String description = Messages.getText(messages.getLast());
            logger.debug("Sending request to {} attempt {}: {}",
                         contextManager.getModels().nameOf(model), attempt, LogDescription.getShortDescription(description, 12));

            response = doSingleSendMessage(model, messages, tools, toolChoice, echo);
            var cr = response.chatResponse;
            boolean isEmpty = cr == null || (Messages.getText(cr.aiMessage()).isEmpty()) && !cr.aiMessage().hasToolExecutionRequests();
            lastError = response.error;
            if (!isEmpty && (lastError == null || allowPartialResponses)) {
                // Success!
                return response;
            }

            // don't retry on bad request errors
            if (lastError != null && lastError.getMessage().contains("BadRequestError")) {
                logger.debug("Stopping on BadRequestError", lastError);
                break;
            }

            logger.debug("LLM error == {}, isEmpty == {}. Will retry. Attempt={}", lastError, isEmpty, attempt);
            if (attempt == maxAttempts) {
                break; // done
            }
            // wait between attempts
            long backoffSeconds = 1L << (attempt - 1);
            backoffSeconds = Math.min(backoffSeconds, 16L);

            // Busywait with countdown
            if (backoffSeconds > 1) {
                io.systemOutput(String.format("LLM issue on attempt %d/%d (retrying in %d seconds).", attempt, maxAttempts, backoffSeconds));
            } else {
                io.systemOutput(String.format("LLM issue on attempt %d/%d (retrying).", attempt, maxAttempts, backoffSeconds));
            }
            long endTime = System.currentTimeMillis() + backoffSeconds * 1000;
            while (System.currentTimeMillis() < endTime) {
                long remain = endTime - System.currentTimeMillis();
                if (remain <= 0) break;
                io.actionOutput("Retrying in %.1f seconds...".formatted(remain / 1000.0));
                Thread.sleep(Math.min(remain, 100));
            }
        }

        // If we get here, we failed all attempts
        if (lastError == null) {
            // LLM returned empty or null - log error to the current request's file
            var dummy = ChatResponse.builder().aiMessage(new AiMessage("Empty response after max retries")).build();
            return new StreamingResult(dummy, new IllegalStateException("Empty response after max retries"));
        }
        // Return last error - log error to the current request's file
        var cr = ChatResponse.builder().aiMessage(new AiMessage("Error: " + lastError.getMessage())).build();
        return new StreamingResult(cr, response.originalResponse(), lastError);
    }

    /**
     * Sends messages to model in a single attempt. If the model doesn't natively support
     * function calling for these tools, we emulate it using a JSON Schema approach.
     * This method now also triggers writing the request to the history file.
     */
    private StreamingResult doSingleSendMessage(StreamingChatLanguageModel model,
                                                List<ChatMessage> messages,
                                                List<ToolSpecification> tools,
                                                ToolChoice toolChoice,
                                                boolean echo) throws InterruptedException
    {
        // Note: writeRequestToHistory is now called *within* this method,
        // right before doSingleStreamingCall, to ensure it uses the final `messagesToSend`.

        var messagesToSend = messages;
        // Preprocess messages *only* if no tools are being requested for this call.
        // This handles the case where prior TERMs exist in history but the current
        // request doesn't involve tools (which makes Anthropic unhappy if it sees it).
        if (tools.isEmpty()) {
            messagesToSend = Llm.emulateToolExecutionResults(messages);
        }

        if (!tools.isEmpty() && contextManager.getModels().requiresEmulatedTools(model)) {
            // Emulation handles its own preprocessing
            return emulateTools(model, messagesToSend, tools, toolChoice, echo);
        }

        // If native tools are used, or no tools, send the (potentially preprocessed if tools were empty) messages.
        var requestBuilder = ChatRequest.builder().messages(messagesToSend);
        if (!tools.isEmpty()) {
            logger.debug("Performing native tool calls");
            var paramsBuilder = getParamsBuilder()
                    .toolSpecifications(tools);
            if (contextManager.getModels().supportsParallelCalls(model)) {
                // can't just blindly call .parallelToolCalls(boolean), litellm will barf if it sees the option at all
                paramsBuilder = paramsBuilder
                        .parallelToolCalls(true);
            }
            var effort = ((OpenAiChatRequestParameters) model.defaultRequestParameters()).reasoningEffort();
            if (toolChoice == ToolChoice.REQUIRED && effort == null) {
                // Anthropic only supports TC.REQUIRED with reasoning off
                // (and Anthropic is currently the only vendor that runs with native tool calls)
                paramsBuilder = paramsBuilder
                        .toolChoice(ToolChoice.REQUIRED);
            }
            requestBuilder.parameters(paramsBuilder.build());
        }

        var request = requestBuilder.build();
        return doSingleStreamingCall(request, echo);
    }

    private OpenAiChatRequestParameters.Builder getParamsBuilder() {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder();

        if (this.tagRetain) {
            // this is the only place we add metadata so we can just overwrite what's there
            logger.trace("Adding 'retain' metadata tag to LLM request.");
            Map<String, String> newMetadata = new java.util.HashMap<>();
            newMetadata.put("tags", "retain");
            builder.metadata(newMetadata);
        }

        return builder;
    }

    /**
     * Emulates function calling for models that don't natively support it.
     * We have two approaches:
     * 1. Schema-based: For models that support JSON schema in response_format
     * 2. Text-based: For models without schema support, using text instructions
     */
    private StreamingResult emulateTools(StreamingChatLanguageModel model,
                                         List<ChatMessage> messages,
                                         List<ToolSpecification> tools,
                                         ToolChoice toolChoice,
                                         boolean echo) throws InterruptedException
    {
        var enhancedTools = ensureThinkToolPresent(tools);
        if (contextManager.getModels().supportsJsonSchema(model)) {
            return emulateToolsUsingJsonSchema(messages, enhancedTools, toolChoice, echo);
        } else {
            return emulateToolsUsingJsonObject(messages, enhancedTools, toolChoice, echo);
        }
    }

    /**
     * Common helper for emulating function calling tools using JSON output
     */
    private StreamingResult emulateToolsCommon(List<ChatMessage> messages,
                                               List<ToolSpecification> tools,
                                               ToolChoice toolChoice,
                                               boolean echo,
                                               Function<List<ChatMessage>, ChatRequest> requestBuilder,
                                               Function<Throwable, String> retryInstructionsProvider) throws InterruptedException
    {
        // FIXME we only log the last of the requests performed which could cause difficulty troubleshooting
        assert !tools.isEmpty();

        // Pre-process messages to combine tool results with subsequent user messages for emulation
        List<ChatMessage> initialProcessedMessages = Llm.emulateToolExecutionResults(messages);

        final int maxTries = 3;
        List<ChatMessage> attemptMessages = new ArrayList<>(initialProcessedMessages);

        ChatRequest   lastRequest; // for logging
        StreamingResult finalResult; // what we will return (and have logged)

        for (int attempt = 1; true; attempt++) {
            // Perform the request for THIS attempt
            lastRequest = requestBuilder.apply(attemptMessages);
            StreamingResult rawResult = doSingleStreamingCallInternal(lastRequest, echo);

            // Fast-fail on transport / HTTP errors (no retry)
            if (rawResult.error() != null) {
                finalResult = rawResult;           // will be logged below
                break;
            }

            // Now parse the JSON
            try {
                StreamingResult parseResult = parseJsonToToolRequests(rawResult, objectMapper);
                if (!parseResult.chatResponse().aiMessage().hasToolExecutionRequests()
                    && toolChoice == ToolChoice.REQUIRED)
                {
                    // REQUIRED but none produced – force retry
                    throw new IllegalArgumentException("No 'tool_calls' found in JSON");
                }

                if (echo) {
                    // output the thinking tool's contents
                    contextManager.getIo().llmOutput(parseResult.chatResponse.aiMessage().text(), ChatMessageType.AI);
                }

                // we got tool calls, or they're optional -- we're done
                finalResult = parseResult;
                break;
            } catch (IllegalArgumentException parseError) {
                // JSON invalid or lacked tool_calls
                if (attempt == maxTries) {
                    // create dummy result for failure
                    var cr = ChatResponse.builder().aiMessage(new AiMessage("Error: " + parseError.getMessage()));
                    finalResult = new StreamingResult(cr.build(), parseError);
                    break; // out of retry loop
                }

                // Add the model’s invalid output and user instructions, then retry
                io.llmOutput("\nRetry " + attempt + "/" + (maxTries - 1)
                                     + ": invalid JSON response; requesting proper format.",
                             ChatMessageType.CUSTOM);
                attemptMessages.add(new AiMessage(rawResult.chatResponse().aiMessage().text()));
                attemptMessages.add(new UserMessage(retryInstructionsProvider.apply(parseError)));
            }
        }

        // All retries exhausted OR fatal error occurred
        assert finalResult != null;
        logRequest(this.model, lastRequest, finalResult);
        return finalResult;
    }

    /**
     * Preprocesses messages for models requiring tool emulation by combining
     * sequences of ToolExecutionResultMessages with the *subsequent* UserMessage.
     * <p>
     * If a sequence of one or more ToolExecutionResultMessages is immediately
     * followed by a UserMessage, the results are formatted as text and prepended
     * to the UserMessage's content. If the results are followed by a different
     * message type or are at the end of the list. Throws IllegalArgumentException
     * if this condition is violated.
     *
     * @param originalMessages The original list of messages.
     * @return A new list with tool results folded into subsequent UserMessages,
     * or the original list if no modifications were needed.
     * @throws IllegalArgumentException if ToolExecutionResultMessages are not followed by a UserMessage.
     */
    static List<ChatMessage> emulateToolExecutionResults(List<ChatMessage> originalMessages) {
        var processedMessages = new ArrayList<ChatMessage>();
        var pendingTerms = new ArrayList<ToolExecutionResultMessage>();
        for (var msg : originalMessages) {
            if (msg instanceof ToolExecutionResultMessage term) {
                // Collect consecutive tool results to group together
                pendingTerms.add(term);
                continue;
            }

            if (!pendingTerms.isEmpty()) {
                // Current message is not a tool result. Check if we have pending results.
                String formattedResults = formatToolResults(pendingTerms);
                if (msg instanceof UserMessage userMessage) {
                    // Combine pending results with this user message
                    String combinedContent = formattedResults + "\n" + Messages.getText(userMessage);
                    UserMessage updatedUserMessage = new UserMessage(userMessage.name(), combinedContent);
                    processedMessages.add(updatedUserMessage);
                    logger.debug("Prepended {} tool result(s) to subsequent user message.", pendingTerms.size());
                } else {
                    // Create a UserMessage to hold the tool calls
                    processedMessages.add(new UserMessage(formattedResults));
                    processedMessages.add(msg);
                }
                // Clear pending results as they've been handled
                pendingTerms.clear();
                continue;
            }

            if (msg instanceof AiMessage) {
                // pull the tool requests into plaintext, OpenAi is fine with it but it confuses Anthropic and Gemini
                // to see tool requests in the history if there are no tools defined for the current request
                processedMessages.add(new AiMessage(Messages.getRepr(msg)));
                continue;
            }

            // else just add the original message
            processedMessages.add(msg);
        }

        // Handle any trailing tool results - this is invalid.
        if (!pendingTerms.isEmpty()) {
            var formattedResults = formatToolResults(pendingTerms);
            processedMessages.add(new UserMessage(formattedResults));
        }

        return processedMessages;
    }

    private static @NotNull String formatToolResults(ArrayList<ToolExecutionResultMessage> pendingTerms) {
        return pendingTerms.stream()
                .map(tr -> """
                        <toolcall id="%s" name="%s">
                        %s
                        </toolcall>
                        """.stripIndent().formatted(tr.id(), tr.toolName(), tr.text()))
                .collect(Collectors.joining("\n"));
    }


    /**
     * Emulates function calling for models that support structured output with JSON schema
     */
    private StreamingResult emulateToolsUsingJsonSchema(List<ChatMessage> messages,
                                                        List<ToolSpecification> tools,
                                                        ToolChoice toolChoice,
                                                        boolean echo) throws InterruptedException
    {
        // Build a top-level JSON schema with "tool_calls" as an array of objects
        var toolNames = tools.stream().map(ToolSpecification::name).distinct().toList();
        var schema = buildToolCallsSchema(toolNames);
        var responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build();
        var requestParams = getParamsBuilder()
                .responseFormat(responseFormat)
                .build();

        Function<Throwable, String> retryInstructionsProvider = e -> """
                %s
                Please ensure you only return a JSON object matching the schema:
                  {
                    "tool_calls": [
                      {
                        "name": "...",
                        "arguments": { ... }
                      },
                      {
                        "name": "...",
                        "arguments": { ... }
                      }
                    ]
                  }
                """.stripIndent().formatted(e == null ? "" : "Your previous response was invalid or did not contain tool_calls: " + e.getMessage());

        // We'll add a user reminder to produce a JSON that matches the schema
        var instructions = getInstructions(tools, retryInstructionsProvider);
        var modified = new UserMessage(Messages.getText(messages.getLast()) + "\n\n" + instructions);
        var initialMessages = new ArrayList<>(messages);
        initialMessages.set(initialMessages.size() - 1, modified);
        logger.trace("Modified messages are {}", initialMessages);

        // Build request creator function
        Function<List<ChatMessage>, ChatRequest> requestBuilder = attemptMessages ->
                ChatRequest.builder()
                        .messages(attemptMessages)
                        .parameters(requestParams)
                        .build();

        return emulateToolsCommon(initialMessages, tools, toolChoice, echo, requestBuilder, retryInstructionsProvider);
    }

    /**
     * Emulates function calling for models that don't support schema but can output JSON based on text instructions
     */
    private StreamingResult emulateToolsUsingJsonObject(List<ChatMessage> messages,
                                                        List<ToolSpecification> tools,
                                                        ToolChoice toolChoice,
                                                        boolean echo) throws InterruptedException
    {
        Function<Throwable, String> retryInstructionsProvider = e -> """
                %s
                Respond with a single JSON object containing a `tool_calls` array. Each entry in the array represents one invocation of a tool.
                No additional keys or text are allowed outside of that JSON object.
                Each tool call must have a `name` that matches one of the available tools, and an `arguments` object containing valid parameters as required by that tool.
                
                Here is the format visualized, where $foo indicates that you will make appropriate substitutions for the given tool call
                {
                  "tool_calls": [
                    {
                      "name": "$tool_name1",
                      "arguments": {
                        "$arg1": "$value1",
                        "$arg2": "$value2",
                        ...
                      }
                    },
                    {
                      "name": "$tool_name2",
                      "arguments": {
                        "$arg3": "$value3",
                        "$arg4": "$value4",
                        ...
                      }
                    }
                  ]
                }
                """.stripIndent().formatted(e == null ? "" : "Your previous response was not valid: " + e.getMessage());

        // Check if we've already added tool instructions to any message
        boolean instructionsPresent = messages.stream().anyMatch(m ->
                                                                         Messages.getText(m).contains("available tools:") &&
                                                                                 Messages.getText(m).contains("tool_calls"));

        logger.debug("Tool emulation sending {} messages with instructionsPresent={}", messages.size(), instructionsPresent);

        // Prepare messages, possibly adding instructions
        List<ChatMessage> initialMessages = new ArrayList<>(messages);
        if (!instructionsPresent) {
            var instructions = getInstructions(tools, retryInstructionsProvider);
            var lastMessage = messages.getLast();
            var modified = new UserMessage(Messages.getText(lastMessage) + "\n\n" + instructions);
            initialMessages.set(initialMessages.size() - 1, modified);
            logger.trace("Added tool instructions to last message");
        }

        // Simple request builder for JSON output format
        Function<List<ChatMessage>, ChatRequest> requestBuilder = attemptMessages -> ChatRequest.builder()
                .messages(attemptMessages)
                .parameters(ChatRequestParameters.builder()
                                    .responseFormat(ResponseFormat.builder()
                                                            .type(ResponseFormatType.JSON)
                                                            .build())
                                    .build())
                .build();

        return emulateToolsCommon(initialMessages, tools, toolChoice, echo, requestBuilder, retryInstructionsProvider);
    }


    /**
     * Builds a JSON schema describing exactly:
     * {
     * "tool_calls": [
     * {
     * "name": "oneOfTheTools",
     * "arguments": { ... arbitrary object ...}
     * }
     * ]
     * }
     * We do not attempt fancy anyOf references here (not all providers support them).
     */
    private static JsonSchema buildToolCallsSchema(List<String> toolNames) {
        // name => enum of tool names
        var nameSchema = JsonEnumSchema.builder()
                .enumValues(toolNames)
                .description("Name of the tool to call; must be one of: " + String.join(", ", toolNames))
                .build();

        // arguments => free-form object
        // FIXME this should really use anyOf, but that's not supported by LLMs today
        var argumentsSchema = JsonObjectSchema.builder()
                .description("Tool arguments object (specific structure depends on the tool).")
                .build();

        // each item => { name, arguments }
        var itemSchema = JsonObjectSchema.builder()
                .addProperty("name", nameSchema)
                .addProperty("arguments", argumentsSchema)
                .required("name", "arguments")
                .build();

        // array property "tool_calls"
        var toolCallsArray = JsonArraySchema.builder()
                .description("All tool calls to be made in sequence.")
                .items(itemSchema)
                .build();

        // top-level object
        var rootSchema = JsonObjectSchema.builder()
                .addProperty("tool_calls", toolCallsArray)
                .required("tool_calls")
                .description("Top-level object containing a 'tool_calls' array describing calls to be made.")
                .build();

        return JsonSchema.builder()
                .name("ToolCalls")
                .rootElement(rootSchema)
                .build();
    }

    /**
     * Parse the model's JSON response into a ChatResponse that includes ToolExecutionRequests.
     * Expects the top-level to have a "tool_calls" array (or the root might be that array).
     */
    private static StreamingResult parseJsonToToolRequests(StreamingResult result, ObjectMapper mapper) {
        String rawText = result.chatResponse.aiMessage().text();
        logger.trace("parseJsonToToolRequests: rawText={}", rawText);

        JsonNode root;
        try {
            root = mapper.readTree(rawText);
        } catch (JsonProcessingException e) {
            // Sometimes the model wraps the JSON in markdown fences ```json ... ```
            int firstFence = rawText.indexOf("```");
            // Find the closing fence after the opening one
            int lastFence = rawText.lastIndexOf("```");
            if (lastFence <= firstFence) {
                logger.debug("Invalid JSON", e);
                throw new IllegalArgumentException("Invalid JSON response: " + e.getMessage());
            }

            // Extract text between fences, removing potential language identifier and trimming whitespace
            String fencedText = rawText.substring(firstFence + 3, lastFence).strip();
            // Handle optional language identifier like "json"
            if (fencedText.toLowerCase().startsWith("json")) {
                fencedText = fencedText.substring(4).stripLeading();
            }

            // Try parsing the fenced content
            try {
                root = mapper.readTree(fencedText);
            } catch (JsonProcessingException e2) {
                // Fenced content is also invalid JSON
                logger.debug("Invalid JSON inside fences", e2);
                throw new IllegalArgumentException("Invalid JSON inside ``` fences: " + e2.getMessage());
            }
        }

        JsonNode toolCallsNode;
        if (root.has("tool_calls") && root.get("tool_calls").isArray()) {
            // happy path, this is what we asked for
            toolCallsNode = root.get("tool_calls");
        } else if (root.isArray()) {
            // some models like to give a top-level array instead
            toolCallsNode = root;
        } else {
            throw new IllegalArgumentException("Response does not contain a 'tool_calls' array");
        }

        // Transform json into tool execution requests, special-casing "think" calls
        var toolExecutionRequests = new ArrayList<ToolExecutionRequest>();
        var thinkReasoning = new ArrayList<String>();

        for (int i = 0; i < toolCallsNode.size(); i++) {
            JsonNode toolCall = toolCallsNode.get(i);
            if (!toolCall.has("name") || !toolCall.has("arguments")) {
                throw new IllegalArgumentException("Tool call object is missing 'name' or 'arguments' field at index " + i);
            }

            String toolName = toolCall.get("name").asText();
            JsonNode arguments = toolCall.get("arguments");

            if (!arguments.isObject()) {
                throw new IllegalArgumentException("tool_calls[" + i + "] provided non-object arguments " + arguments);
            }
            String argsStr = arguments.toString(); // Preserve raw JSON for execution

            if ("think".equals(toolName)) {
                // Extract reasoning from the "think" tool
                if (!arguments.has("reasoning") || !arguments.get("reasoning").isTextual()) {
                    throw new IllegalArgumentException("Found 'think' tool call without a textual 'reasoning' argument at index " + i);
                }
                thinkReasoning.add(arguments.get("reasoning").asText());
            }
            var toolExecutionRequest = ToolExecutionRequest.builder()
                    .id(String.valueOf(i))
                    .name(toolName)
                    .arguments(argsStr)
                    .build();
            toolExecutionRequests.add(toolExecutionRequest);
        }
        logger.trace("Generated tool execution requests: {}", toolExecutionRequests);

        String aiMessageText;
        if (thinkReasoning.isEmpty()) {
            aiMessageText = "";
        } else {
            aiMessageText = String.join("\n\n", thinkReasoning); // Merged reasoning becomes the message text
        }

        // Create a properly formatted AiMessage
        var aiMessage = toolExecutionRequests.isEmpty()
                ? AiMessage.from(aiMessageText)
                : new AiMessage(aiMessageText, toolExecutionRequests);

        var cr = ChatResponse.builder().aiMessage(aiMessage).build();
        // Pass the original raw response alongside the parsed one
        return new StreamingResult(cr, result.originalResponse, null);
    }

    private static String getInstructions(List<ToolSpecification> tools, Function<Throwable, String> retryInstructionsProvider) {
        String toolsDescription = tools.stream()
                .map(tool -> {
                    var parametersInfo = tool.parameters().properties().entrySet().stream()
                            .map(entry -> {
                                var schema = entry.getValue();
                                String description;
                                String type;

                                // this seems unnecessarily clunky but the common interface does not offer anything useful
                                switch (schema) {
                                    case JsonStringSchema jsSchema -> {
                                        description = jsSchema.description();
                                        type = "string";
                                    }
                                    case JsonArraySchema jaSchema -> {
                                        description = jaSchema.description();
                                        var itemSchema = jaSchema.items();
                                        String itemType = switch (itemSchema) {
                                            case JsonStringSchema __ -> "string";
                                            case JsonIntegerSchema __ -> "integer";
                                            case JsonNumberSchema __ -> "number";
                                            case JsonBooleanSchema __ -> "boolean";
                                            default ->
                                                    throw new IllegalArgumentException("Unsupported array item type: " + itemSchema);
                                        };
                                        type = "array of %s".formatted(itemType);
                                    }
                                    case JsonIntegerSchema jiSchema -> {
                                        description = jiSchema.description();
                                        type = "integer";
                                    }
                                    case JsonNumberSchema jnSchema -> {
                                        description = jnSchema.description();
                                        type = "number";
                                    }
                                    case JsonBooleanSchema jbSchema -> {
                                        description = jbSchema.description();
                                        type = "boolean";
                                    }
                                    default -> throw new IllegalArgumentException("Unsupported schema type: " + schema);
                                }
                                assert description != null : "null description for " + entry;

                                return """
                                        <parameter name="%s" type="%s" required="%s">
                                        %s
                                        </parameter>
                                        """.stripIndent().formatted(
                                        entry.getKey(),
                                        type,
                                        tool.parameters().required().contains(entry.getKey()),
                                        description
                                );
                            })
                            .collect(Collectors.joining("\n"));

                    return """
                            <tool name="%s">
                            %s
                            %s
                            </tool>
                            """.stripIndent().formatted(tool.name(),
                                                        tool.description(),
                                                        parametersInfo.isEmpty() ? "(No parameters)" : parametersInfo);
                }).collect(Collectors.joining("\n"));

        // if you change this you probably also need to change emulatedToolInstructionsPresent
        return """
                %d available tools:
                %s
                
                %s
                
                Include all the tool calls necessary to satisfy the request in a single object!
                """.stripIndent().formatted(tools.size(), toolsDescription, retryInstructionsProvider.apply(null));
    }

    /**
     * Ensures the "think" tool is present in the tools list for emulation, but only for models
     * that are *not* designated as "reasoning" models (which are expected to think implicitly).
     * This provides a consistent way for non-reasoning models to reason through complex problems.
     *
     * @param originalTools The original list of tool specifications.
     * @return A new list containing all original tools plus the think tool if not already present
     * and the model is not a designated reasoning model.
     */
    private List<ToolSpecification> ensureThinkToolPresent(List<ToolSpecification> originalTools) {
        // Check if the think tool is already in the list
        boolean hasThinkTool = originalTools.stream()
                .anyMatch(tool -> "think".equals(tool.name()));
        if (hasThinkTool) {
            return originalTools;
        }

        // Add the think tool only if the model is not a reasoning model
        if (!contextManager.getModels().isReasoning(this.model)) {
            logger.debug("Adding 'think' tool for non-reasoning model {}", contextManager.getModels().nameOf(this.model));
            var enhancedTools = new ArrayList<>(originalTools);
            enhancedTools.addAll(contextManager.getToolRegistry().getRegisteredTools(List.of("think")));
            return enhancedTools;
        }
        logger.debug("Skipping 'think' tool for reasoning model {}", contextManager.getModels().nameOf(this.model));
        return originalTools;
    }

    /**
     * Writes history information to session-specific files.
     */
    private synchronized void logRequest(StreamingChatLanguageModel model, ChatRequest request, StreamingResult result) {
        if (sessionHistoryDir == null) {
            // History directory creation failed in constructor, do nothing.
            return;
        }
        try {
            var timestamp = LocalDateTime.now(); // timestamp finished, not started

            var formattedRequest = "# Request to %s:\n\n%s\n".formatted(contextManager.getModels().nameOf(model),
                                                                        TaskEntry.formatMessages(request.messages()));
            var formattedTools = request.toolSpecifications() == null ? "" : "# Tools:\n\n" + request.toolSpecifications().stream().map(ToolSpecification::name).collect(Collectors.joining("\n"));
            var formattedResponse = result == null
                                    ? "# Response:\n\nCancelled"
                                    : "# Response:\n\n%s".formatted(result.formatted());
            String fileTimestamp = timestamp.format(DateTimeFormatter.ofPattern("HH-mm-ss"));
            String shortDesc = result == null ? "Cancelled" : LogDescription.getShortDescription(result.getDescription());
            var filePath = sessionHistoryDir.resolve(String.format("%s %s.log", fileTimestamp, shortDesc));
            var options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
            logger.trace("Writing history to file {}", filePath);
            // Ensure the filename is unique before writing
            var uniqueFilePath = filePath;
            int suffix = 1;
            while (Files.exists(uniqueFilePath)) {
                var newFilePath = filePath.toString();
                int dotIndex = newFilePath.lastIndexOf('.');
                if (dotIndex > 0) {
                    newFilePath = newFilePath.substring(0, dotIndex) + "-" + suffix + newFilePath.substring(dotIndex);
                } else {
                    newFilePath += "-" + suffix;
                }
                uniqueFilePath = Path.of(newFilePath);
                suffix++;
            }
            logger.trace("Writing history to file {}", uniqueFilePath);
            Files.writeString(uniqueFilePath, formattedRequest + formattedTools + formattedResponse, options);
        } catch (IOException e) {
            logger.error("Failed to write LLM history file", e);
        }
    }

    /**
     * The result of a streaming call:
     * - chatResponse: processed response with tool emulation
     * - originalResponse: exactly what we got back from the LLM
     * - error: like it says
     *
     * Generally, only one of (chatResponse, error) is not null, but there is one edge case:
     * if the LLM hangs up abruptly after starting its response, we'll forge a chatResponse with the partial result
     * and also include the error that we got from the HTTP layer. In this case originalResponse will be null
     */
    public record StreamingResult(ChatResponse chatResponse,
                                  ChatResponse originalResponse,
                                  Throwable error)
    {
        public StreamingResult(ChatResponse chatResponse, Throwable error) {
            this(chatResponse, chatResponse, error);
        }

        public StreamingResult {
            // Must have either a chatResponse or an error
            assert error != null || chatResponse != null;
        }

        public String formatted() {
            if (error != null) {
                return """
                       [Error: %s]
                       %s
                       """.formatted(formatThrowable(error), originalResponse == null ? "[Null response]" : originalResponse.toString());
            }

            return originalResponse.toString();
        }

        private String formatThrowable(Throwable th) {
            var baos = new ByteArrayOutputStream();
            try (var ps = new PrintStream(baos)) {
                th.printStackTrace(ps);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }

        /**
         * Generates a short description of the result for logging purposes.
         *
         * @return A short description string.
         */
        private String getDescription() {
            if (error != null) {
                return error.getMessage();
            }

            assert chatResponse != null;
            var aiMessage = chatResponse.aiMessage();
            if (aiMessage.hasToolExecutionRequests()) {
                return aiMessage.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::name)
                        .collect(Collectors.joining(", "));
            }
            var text = aiMessage.text();
            if (text != null && !text.isBlank()) {
                return text;
            }
            return "empty response";
        }
    }
}
