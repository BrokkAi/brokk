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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.github.jbellis.brokk.SessionResult.getShortDescription;

/**
 * The main orchestrator for sending requests to an LLM, possibly with tools, collecting
 * streaming responses, etc.
 */
public class Coder {
    private static final Logger logger = LogManager.getLogger(Coder.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final IConsoleIO io;
    private final Path sessionHistoryDir; // Directory for this session's history files
    final IContextManager contextManager;
    private final int MAX_ATTEMPTS = 8; // Keep retry logic for now
    private final StreamingChatLanguageModel model;

    public Coder(StreamingChatLanguageModel model, String taskDescription, IContextManager contextManager) {
        this.model = model;
        this.contextManager = contextManager;
        this.io = contextManager.getIo();
        var sourceRoot = contextManager.getProject().getRoot();
        var historyBaseDir = sourceRoot.resolve(".brokk").resolve("llm-history");

        // Create session directory name
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        // Use the static method from CodeAgent.SessionResult for consistency
        var sessionDesc = getShortDescription(taskDescription);
        var sessionDirName = String.format("%s %s", timestamp, sessionDesc);
        this.sessionHistoryDir = historyBaseDir.resolve(sessionDirName);

        // Create the directory
        try {
            Files.createDirectories(this.sessionHistoryDir);
        } catch (IOException e) {
            logger.error("Failed to create history directory {}", this.sessionHistoryDir, e);
        }
    }

    /**
     * Actually performs one streaming call to the LLM, returning once the response
     * is done or there's an error. If 'echo' is true, partial tokens go to console.
     */
    private StreamingResult doSingleStreamingCall(ChatRequest request, boolean echo) {
        var result = doSingleStreamingCallInternal(request, echo);
        logRequest(model, request, result);
        return result;
    }

    private StreamingResult doSingleStreamingCallInternal(ChatRequest request, boolean echo) {
        // latch for awaiting the complete response
        var latch = new CountDownLatch(1);
        var canceled = new AtomicBoolean(false);
        var lock = new ReentrantLock();
        var errorRef = new AtomicReference<Throwable>(null);
        var outputTokenCountRef = new AtomicReference<>(-1);
        var atomicResponse = new AtomicReference<ChatResponse>();

        Consumer<Runnable> ifNotCancelled = (r) -> {
            lock.lock();
            try {
                if (!canceled.get()) {
                    r.run();
                }
            } finally {
                lock.unlock();
            }
        };

        if (Thread.currentThread().isInterrupted()) {
            return new StreamingResult(null, true, null);
        }
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        io.llmOutput(token, ChatMessageType.AI);
                        io.hideOutputSpinner();
                    }
                });
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                ifNotCancelled.accept(() -> {
                    io.hideOutputSpinner();
                    if (echo) {
                        io.llmOutput("\n", ChatMessageType.AI);
                    }
                    atomicResponse.set(response);
                    if (response == null) {
                        // I think this isn't supposed to happen, but seeing it when litellm throws back a 400.
                        // Fake an exception so the caller can treat it like other errors
                        errorRef.set(new HttpException(400, "BadRequestError (no further information, the response was null; check litellm logs)"));
                    } else {
                        if (response.tokenUsage() == null) {
                            logger.warn("Null token usage !? in {}", response);
                        } else {
                            outputTokenCountRef.set(response.tokenUsage().outputTokenCount());
                        }
                    }
                    latch.countDown();
                });
            }

            @Override
            public void onError(Throwable th) {
                ifNotCancelled.accept(() -> {
                    logger.debug("LLM error", th);
                    io.hideOutputSpinner();
                    io.toolErrorRaw("LLM error: " + th.getMessage());
                    // Instead of interrupting, just record it so we can retry from the caller
                    errorRef.set(th);
                    latch.countDown();
                });
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            lock.lock();
            canceled.set(true);
            lock.unlock();
            // We were interrupted while waiting
            return new StreamingResult(null, true, null);
        }

        var streamingError = errorRef.get();
        var outputTokenCount = outputTokenCountRef.get();
        if (streamingError != null) {
            // Return an error result
            return new StreamingResult(null, outputTokenCount, false, streamingError);
        }

        var cr = atomicResponse.get();
        if (cr == null) {
            // also an error
            return new StreamingResult(null, outputTokenCount, false, new IllegalStateException("No ChatResponse from model"));
        }
        return new StreamingResult(cr, outputTokenCount, false, null);
    }

    /**
     * Sends a user query to the LLM with streaming. Tools are not used.
     * Writes to conversation history. Optionally echoes partial tokens to the console.
     *
     * @param messages The messages to send
     * @param echo Whether to echo LLM responses to the console as they stream
     * @return The final response from the LLM as a record containing ChatResponse, errors, etc.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages, boolean echo) {
        return sendMessageWithRetry(messages, List.of(), ToolChoice.AUTO, echo, MAX_ATTEMPTS);
    }

    /**
     * Sends messages to a given model, no tools, no streaming echo.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages) {
        return sendRequest(messages, false);
    }

    /**
     * Sends messages to a model with possible tools and a chosen tool usage policy.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages,
                                       List<ToolSpecification> tools,
                                       ToolChoice toolChoice,
                                       boolean echo) {
        var result = sendMessageWithRetry(messages, tools, toolChoice, echo, MAX_ATTEMPTS);
        var cr = result.chatResponse();

        // poor man's ToolChoice.REQUIRED (not supported by langchain4j for Anthropic)
        // Also needed for our emulation if it returns a response without a tool call
        while (!result.cancelled
                && result.error == null
                && !tools.isEmpty()
                && !cr.aiMessage().hasToolExecutionRequests()
                && toolChoice == ToolChoice.REQUIRED) {
            logger.debug("Enforcing tool selection");
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
    private StreamingResult sendMessageWithRetry(List<ChatMessage> messages,
                                                 List<ToolSpecification> tools,
                                                 ToolChoice toolChoice,
                                                 boolean echo,
                                                 int maxAttempts)
    {
        Throwable lastError = null;
        int attempt = 0;
        // Get model name once using instance field
        String modelName = contextManager.getModels().nameOf(model);

        while (attempt++ < maxAttempts) {
            logger.debug("Sending request to {} attempt {}: {}",
                         modelName, attempt, SessionResult.getShortDescription(Models.getText(messages.getLast()), 12));

            if (echo) {
                io.showOutputSpinner("Thinking...");
            }

            var response = doSingleSendMessage(model, messages, tools, toolChoice, echo);
            if (response.cancelled) {
                return response;
            }
            if (response.error == null) {
                // Check if we got a non-empty response
                var cr = response.chatResponse;
                boolean isEmpty = (cr.aiMessage().text() == null || cr.aiMessage().text().isBlank())
                        && !cr.aiMessage().hasToolExecutionRequests();
                if (!isEmpty) {
                    return response;
                }
            }
            // some error or empty response
            lastError = response.error;
            if (lastError != null && lastError.getMessage().contains("BadRequestError")) {
                // don't retry on bad request errors
                break;
            }

            logger.debug("LLM error / empty message. Will retry. Attempt={}", attempt);
            if (attempt == maxAttempts) {
                break; // done
            }
            // wait between attempts
            long backoffSeconds = 1L << (attempt - 1);
            backoffSeconds = Math.min(backoffSeconds, 16L);

            // Busywait with countdown
            io.systemOutput(String.format("LLM issue on attempt %d/%d (retrying in %d seconds).", attempt, maxAttempts, backoffSeconds));
            try {
                long endTime = System.currentTimeMillis() + backoffSeconds * 1000;
                while (System.currentTimeMillis() < endTime) {
                    long remain = endTime - System.currentTimeMillis();
                    if (remain <= 0) break;
                    io.actionOutput("Retrying in %.1f seconds...".formatted(remain / 1000.0));
                    Thread.sleep(Math.min(remain, 100));
                }
            } catch (InterruptedException e) {
                io.systemOutput("Interrupted!");
                return new StreamingResult(null, true, null);
            }
        }

        // If we get here, we failed all attempts
        if (lastError == null) {
            // LLM returned empty or null - log error to the current request's file
            var dummy = ChatResponse.builder().aiMessage(new AiMessage("Empty response after max retries")).build();
            return new StreamingResult(dummy, false, new IllegalStateException("LLM empty or null after max retries"));
        }
        // Return last error - log error to the current request's file
        var cr = ChatResponse.builder().aiMessage(new AiMessage("Error: " + lastError.getMessage())).build();
        return new StreamingResult(cr, false, lastError);
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
                                                boolean echo) {
        // Note: writeRequestToHistory is now called *within* this method,
        // right before doSingleStreamingCall, to ensure it uses the final `messagesToSend`.

        var messagesToSend = messages;
        // Preprocess messages *only* if no tools are being requested for this call.
        // This handles the case where prior TERMs exist in history but the current
        // request doesn't involve tools (which makes Anthropic unhappy if it sees it).
        if (tools.isEmpty()) {
            messagesToSend = Coder.emulateToolExecutionResults(messages);
        }

        if (!tools.isEmpty() && contextManager.getModels().requiresEmulatedTools(model)) {
            // Emulation handles its own preprocessing
            return emulateTools(model, messagesToSend, tools, toolChoice, echo);
        }

        // If native tools are used, or no tools, send the (potentially preprocessed if tools were empty) messages.
        var builder = ChatRequest.builder().messages(messagesToSend);
        if (!tools.isEmpty()) {
            logger.debug("Performing native tool calls");
            var params = OpenAiChatRequestParameters.builder()
                    .toolSpecifications(tools)
                    .parallelToolCalls(true)
                    .toolChoice(toolChoice)
                    .build();
            builder.parameters(params);
        }

        var request = builder.build();
        return doSingleStreamingCall(request, echo);
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
                                         boolean echo)
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
                                               Function<Throwable, String> retryInstructionsProvider) {
        assert !tools.isEmpty();

        // Preprocess messages to combine tool results with subsequent user messages for emulation
        List<ChatMessage> initialProcessedMessages = Coder.emulateToolExecutionResults(messages);

        // We'll do up to 3 tries
        int maxTries = 3;
        ChatResponse lastResponse = null;
        Throwable lastError = null;
        int outputTokenCount = -1;
        // Use a mutable list for potential retries
        List<ChatMessage> attemptMessages = new ArrayList<>(initialProcessedMessages);

        for (int attempt = 1; attempt <= maxTries; attempt++) {
            var request = requestBuilder.apply(attemptMessages);
            var singleCallResult = doSingleStreamingCall(request, echo);

            if (singleCallResult.cancelled) {
                return singleCallResult; // user interrupt
            }

            lastResponse = singleCallResult.chatResponse;
            lastError = singleCallResult.error;
            outputTokenCount = singleCallResult.outputTokenCount;

            // If an error occurred (like connectivity or 400) let's bail early
            if (lastError != null) {
                break;
            }

            // If there's no AI message, we can't parse
            if (lastResponse == null || lastResponse.aiMessage() == null) {
                lastError = new IllegalArgumentException("No valid ChatResponse or AiMessage from model");
                break;
            }

            // Now parse the JSON
            try {
                StreamingResult parseResult = parseJsonToToolRequests(singleCallResult, objectMapper);
                // If we got tool calls, we are done
                if (parseResult.chatResponse.aiMessage().hasToolExecutionRequests()) {
                    return parseResult;
                } // <-- This closing brace was missing
                // else if no tool calls, but toolChoice= AUTO => it's acceptable
                if (toolChoice == ToolChoice.AUTO) {
                    return singleCallResult;
                }
                // else we wanted at least 1 tool call
                throw new IllegalArgumentException("No 'tool_calls' found in JSON");
            } catch (IllegalArgumentException e) {
                logger.debug("JSON parse failed on attempt {}: {}", attempt, e.getMessage());
                if (attempt == maxTries) {
                    // last try
                    lastError = new RuntimeException("Failed to produce valid tool_calls after " + maxTries + " attempts", e);
                    break;
                }

                // Otherwise, add the failed AI message and a new user message with retry instructions
                io.systemOutput("Retry " + attempt + "/" + (maxTries - 1) + ": Invalid JSON response, requesting proper format.");
                // Add the raw response that failed parsing
                attemptMessages.add(new AiMessage(lastResponse.aiMessage().text()));
                // Add the user message requesting correction
                String instructions = retryInstructionsProvider.apply(e);
                attemptMessages.add(new UserMessage(instructions));
            }
        }

        // If we get here, we have an error or invalid final response
        if (lastResponse == null) {
            // No final response at all
            var failMsg = "No valid response after " + maxTries + " attempts: " + lastError.getMessage();
            logger.warn(failMsg, lastError);
            var dummyResponse = ChatResponse.builder().aiMessage(new AiMessage(failMsg)).build();
            return new StreamingResult(dummyResponse, outputTokenCount, false, new RuntimeException(failMsg));
        }
        // Otherwise we have some final ChatResponse with an error
        var fail = ChatResponse.builder()
                .aiMessage(new AiMessage("Error: " + lastError.getMessage()))
                .build();
        logger.error("Emulated function calling failed: {}", lastError.getMessage());
        return new StreamingResult(fail, outputTokenCount, false, lastError);
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
     *         or the original list if no modifications were needed.
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
                    String combinedContent = formattedResults + "\n" + Models.getText(userMessage);
                    // Preserve name if any
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
                processedMessages.add(new AiMessage(Models.getRepr(msg)));
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
                                                        boolean echo) {
        // Build a top-level JSON schema with "tool_calls" as an array of objects
        var toolNames = tools.stream().map(ToolSpecification::name).distinct().toList();
        var schema = buildToolCallsSchema(toolNames);
        var responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build();
        var requestParams = ChatRequestParameters.builder()
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
        var modified = new UserMessage(Models.getText(messages.getLast()) + "\n\n" + instructions);
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
                                                        boolean echo) {
        var instructionsPresent = emulatedToolInstructionsPresent(messages);
        logger.debug("Tool emulation sending {} messages with {}", messages.size(), instructionsPresent);

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

        List<ChatMessage> initialMessages = new ArrayList<>(messages);
        if (!instructionsPresent) {
            // Inject instructions for the model re how to format function calls
            var instructions = getInstructions(tools, retryInstructionsProvider);
            var modified = new UserMessage(Models.getText(messages.getLast()) + "\n\n" + instructions);
            initialMessages.set(initialMessages.size() - 1, modified);
            logger.trace("Modified messages are {}", initialMessages);
        }

        // Build request creator function
        Function<List<ChatMessage>, ChatRequest> requestBuilder = attemptMessages ->
                ChatRequest.builder()
                        .messages(attemptMessages)
                        .parameters(ChatRequestParameters.builder()
                                            .responseFormat(ResponseFormat.builder()
                                                                    .type(ResponseFormatType.JSON)
                                                                    .build())
                                            .build())
                        .build();

        return emulateToolsCommon(initialMessages, tools, toolChoice, echo, requestBuilder, retryInstructionsProvider);
    }

    private static boolean emulatedToolInstructionsPresent(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> {
            var t = Models.getText(m);
            return t.matches("(?s).*\\d+ available tools:.*")
                    && t.contains("Include all the tool calls");
        });
    }

    /**
     * Builds a JSON schema describing exactly:
     * {
     *   "tool_calls": [
     *     {
     *       "name": "oneOfTheTools",
     *       "arguments": { ... arbitrary object ...}
     *     }
     *   ]
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

        // Transform json into list of tool execution requests
        var toolExecutionRequests = new ArrayList<ToolExecutionRequest>();
        for (int i = 0; i < toolCallsNode.size(); i++) {
            JsonNode toolCall = toolCallsNode.get(i);
            if (!toolCall.has("name") || !toolCall.has("arguments")) {
                throw new IllegalArgumentException("Tool call object is missing 'name' or 'arguments' field");
            }

            String toolName = toolCall.get("name").asText();
            JsonNode arguments = toolCall.get("arguments");

            if (!arguments.isObject()) {
                throw new IllegalArgumentException("tool_calls[" + i + "] provided non-object arguments " + arguments);
            }
            String argsStr = arguments.toString();
            var toolExecutionRequest = ToolExecutionRequest.builder()
                    .id(String.valueOf(i))
                    .name(toolName)
                    .arguments(argsStr)
                    .build();

            toolExecutionRequests.add(toolExecutionRequest);
        }

        logger.trace("Generated tool execution requests: {}", toolExecutionRequests);

        // Create a properly formatted AiMessage with tool execution requests
        var aiMessage = new AiMessage("[json]", toolExecutionRequests);
        var cr = ChatResponse.builder().aiMessage(aiMessage).build();
        return new StreamingResult(cr, result.originalResponse, result.outputTokenCount, false, null);
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
     * Ensures the "think" tool is present in the tools list for emulation.
     * This provides a consistent way for the model to reason through complex problems.
     *
     * @param originalTools The original list of tool specifications
     * @return A new list containing all original tools plus the think tool if not already present
     */
    private List<ToolSpecification> ensureThinkToolPresent(List<ToolSpecification> originalTools) {
        // Check if the think tool is already in the list
        boolean hasThinkTool = originalTools.stream()
                .anyMatch(tool -> "think".equals(tool.name()));
        if (hasThinkTool) {
            return originalTools;
        }

        // Add the think tool
        var enhancedTools = new ArrayList<>(originalTools);
        enhancedTools.addAll(contextManager.getToolRegistry().getRegisteredTools(List.of("think")));
        return enhancedTools;
    }

    /**
     * Writes history information to session-specific files.
     */
    private void logRequest(StreamingChatLanguageModel model, ChatRequest request, StreamingResult result) {
        if (sessionHistoryDir == null) {
            // History directory creation failed in constructor, do nothing.
            return;
        }
        try {
            var timestamp = LocalDateTime.now(); // timestamp finished, not started

            String shortDesc = getShortDescription(getResultDescription(result));
            var formattedRequest = "# Request %s... to %s:\n\n%s\n".formatted(shortDesc,
                                                                              contextManager.getModels().nameOf(model),
                                                                              TaskEntry.formatMessages(request.messages()));
            var formattedTools = request.toolSpecifications() == null ? "" : "# Tools:\n\n" + request.toolSpecifications().stream().map(ToolSpecification::toString).collect(Collectors.joining("\n"));
            var formattedResponse = "# Response:\n\n%s".formatted(result.formatted());
            String fileTimestamp = timestamp.format(DateTimeFormatter.ofPattern("HH-mm-ss"));
            var filePath = sessionHistoryDir.resolve(String.format("%s %s.log", fileTimestamp, shortDesc));
            var options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
            logger.debug("Writing history to new file: {}", filePath);
            Files.writeString(filePath, formattedRequest + formattedTools + formattedResponse, options);
        } catch (IOException e) {
            logger.error("Failed to write LLM history file", e);
        }
    }

    /**
     * Generates a short description of the result for logging purposes.
     *
     * @param result The streaming result to describe.
     * @return A short description string.
     */
    private String getResultDescription(StreamingResult result) {
        if (result.error != null) {
            return result.error.getMessage();
        }
        if (result.cancelled) {
            return "Cancelled";
        }
        assert result.chatResponse != null;
        var aiMessage = result.chatResponse.aiMessage();
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

    /**
     * The result of a streaming call. Usually you only need the final ChatResponse
     * unless cancelled or error is non-null.
     */
    public record StreamingResult(ChatResponse chatResponse,
                                  ChatResponse originalResponse,
                                  int outputTokenCount,
                                  boolean cancelled,
                                  Throwable error) {
        public StreamingResult(ChatResponse chatResponse, boolean cancelled, Throwable error) {
            this(chatResponse, chatResponse, -1, cancelled, error);
        }

        public StreamingResult(ChatResponse chatResponse, int outputTokenCount, boolean cancelled, Throwable error) {
            this(chatResponse, chatResponse, outputTokenCount, cancelled, error);
        }

        public StreamingResult {
            // Must have either a chatResponse or an error/cancel
            assert cancelled || error != null || chatResponse != null;
            assert (originalResponse == null) == (chatResponse == null);
        }

        public String formatted() {
            if (cancelled) {
                return "Cancelled";
            }
            if (error != null) {
                return "Error: " + error.getMessage();
            }

            return originalResponse.toString();
        }
    }
}
