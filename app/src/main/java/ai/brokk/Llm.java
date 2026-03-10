package ai.brokk;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.LogDescription;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.OverthinkingException;
import dev.langchain4j.exception.PaymentRequiredException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.internal.ExceptionMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.shared.StreamOptions;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * The main orchestrator for sending requests to an LLM, possibly with tools, collecting streaming responses, etc.
 *
 * Preserves model chain-of-thought, so you should create a new instance for every conversation.
 */
public class Llm {
    private static final Logger logger = LogManager.getLogger(Llm.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().setVisibility(PropertyAccessor.FIELD, Visibility.ANY);

    /** Base directory where LLM interaction history logs are stored. */
    public static final String HISTORY_DIR_NAME = "llm-history";

    public static Llm create(Options options, IContextManager cm, boolean tagRetain) {
        return new Llm(
                options.model,
                options.task,
                options.type,
                cm,
                options.allowPartialResponses,
                options.forceReasoningEcho,
                tagRetain,
                options.echo);
    }

    /**
     * Builder-like options for creating Llm instances. Mandatory: model and task. Optional toggles via fluent methods.
     */
    public static class Options {
        private final StreamingChatModel model;
        private final String task;
        private final TaskResult.Type type;
        private boolean allowPartialResponses;
        private boolean echo;
        // FIXME this is a hack to keep ContextAgent's file scan from living permanently (until compression)
        // in the message history. This causes surprising behavior, because
        // what the caller gets in StreamingResult (which has response tokens in AiMessage::text) does not
        // match what we put in the TaskResult (which rebuilds it in AiMessage::reasoningContent from what we echo to
        // MOP).
        private boolean forceReasoningEcho;

        public Options(StreamingChatModel model, String task, TaskResult.Type type) {
            this.model = model;
            this.task = task;
            this.type = type;
        }

        public Options withPartialResponses() {
            this.allowPartialResponses = true;
            return this;
        }

        public Options withForceReasoningEcho() {
            this.forceReasoningEcho = true;
            return this;
        }

        public Options withEcho() { // New method to set echo option
            this.echo = true;
            return this;
        }
    }

    private IConsoleIO io;
    private final Path historyBaseDir;
    private final Path historyTaskBaseDir;
    private final String baseTaskDirName;
    private @Nullable Path taskHistoryDir; // Directory for this specific LLM task's history files (created lazily)
    private final TaskResult.Type taskType;
    final IContextManager contextManager;
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final int MAX_TOOL_CONTRACT_RETRIES = 3;
    private final int MAX_ATTEMPTS;
    private StreamingChatModel model;
    private final boolean allowPartialResponses;
    private final boolean forceReasoningEcho;
    private final boolean tagRetain;
    private final boolean echo;

    // Sequence for request/response log files
    private int requestSequence = 1;

    public Llm(
            StreamingChatModel model,
            String taskDescription,
            TaskResult.Type taskType,
            IContextManager contextManager,
            boolean allowPartialResponses,
            boolean forceReasoningEcho,
            boolean tagRetain,
            boolean echo) {
        this.model = model;
        this.taskType = taskType;
        this.contextManager = contextManager;
        this.io = contextManager.getIo();
        this.allowPartialResponses = allowPartialResponses;
        this.forceReasoningEcho = forceReasoningEcho;
        this.tagRetain = tagRetain;
        this.echo = echo;
        this.MAX_ATTEMPTS = determineMaxAttempts();
        logger.trace("MAX_ATTEMPTS configured to {}", this.MAX_ATTEMPTS);
        this.historyBaseDir = getHistoryBaseDir(contextManager.getProject().getRoot());

        if (taskType == TaskResult.Type.SUMMARIZE) {
            this.historyTaskBaseDir = historyBaseDir.resolve("summaries");
        } else if (taskType == TaskResult.Type.NONE) {
            this.historyTaskBaseDir = historyBaseDir.resolve("other");
        } else {
            this.historyTaskBaseDir = historyBaseDir;
        }

        // Store task directory name components for lazy creation
        var timestamp =
                LocalDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        var taskDesc = LogDescription.getShortDescription(taskDescription);
        this.baseTaskDirName = String.format("%s %s %s", timestamp, taskType.displayName(), taskDesc);
    }

    /**
     * Returns the base directory where all LLM task histories are stored for a project.
     *
     * @param projectRoot The root path of the project.
     * @return The Path object representing the base history directory.
     */
    public static Path getHistoryBaseDir(Path projectRoot) {
        return projectRoot.resolve(AbstractProject.BROKK_DIR).resolve(HISTORY_DIR_NAME);
    }

    private static String logFileTimestamp() {
        return LocalDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH-mm.ss"));
    }

    /**
     * Determine MAX_ATTEMPTS, overridable by environment variable BRK_LLM_ATTEMPTS.
     * If the env var is not present or not a positive integer, DEFAULT_MAX_ATTEMPTS is used.
     */
    private int determineMaxAttempts() {
        var env = System.getenv("BRK_LLM_ATTEMPTS");
        if (env == null || env.isBlank()) {
            return DEFAULT_MAX_ATTEMPTS;
        }
        try {
            int parsed = Integer.parseInt(env.trim());
            if (parsed <= 0) {
                logger.warn(
                        "BRK_LLM_ATTEMPTS value '{}' is not a positive integer; falling back to default {}",
                        env,
                        DEFAULT_MAX_ATTEMPTS);
                return DEFAULT_MAX_ATTEMPTS;
            }
            logger.debug("Overriding MAX_ATTEMPTS with BRK_LLM_ATTEMPTS={}", parsed);
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn(
                    "Could not parse BRK_LLM_ATTEMPTS='{}' as integer; falling back to default {}",
                    env,
                    DEFAULT_MAX_ATTEMPTS);
            return DEFAULT_MAX_ATTEMPTS;
        }
    }

    /**
     * Lazily creates and returns the task history directory. Thread-safe.
     */
    private synchronized Path getOrCreateTaskHistoryDir() {
        if (taskHistoryDir == null) {
            synchronized (Llm.class) {
                int suffix = 1;
                var mutableDirName = historyTaskBaseDir.resolve(baseTaskDirName);
                while (Files.exists(mutableDirName)) {
                    var newDirName = baseTaskDirName + "-" + suffix;
                    mutableDirName = historyTaskBaseDir.resolve(newDirName);
                    suffix++;
                }
                taskHistoryDir = mutableDirName;
                try {
                    Files.createDirectories(taskHistoryDir);
                } catch (IOException e) {
                    logger.error("Failed to create task history directory {}", taskHistoryDir, e);
                }
            }
        }
        return taskHistoryDir;
    }

    /**
     * Write the request JSON before sending to the model, to a file named "<base>-request.json".
     * Returns the assigned sequence number so that the corresponding response can use the same number.
     */
    private synchronized int logRequest(ChatRequest request) {
        int assignedSequence = requestSequence++;
        try {
            var dir = getOrCreateTaskHistoryDir();
            var filename = "%s %03d-request.json".formatted(logFileTimestamp(), assignedSequence);
            var requestPath = dir.resolve(filename);
            var requestJson = requestJsonForLogging(request);
            logger.trace("Writing pre-send request JSON to {}", requestPath);
            AtomicWrites.save(requestPath, requestJson);
        } catch (IOException e) {
            logger.error("Failed to write pre-send request JSON", e);
        }
        return assignedSequence;
    }

    /**
     * Produces the JSON string for logging a request. For OpenAI models, this matches the exact
     * wire format sent by the streaming path. For other models, uses the generic ChatRequest serialization.
     */
    String requestJsonForLogging(ChatRequest request) {
        if (model instanceof OpenAiStreamingChatModel openAiModel) {
            // Mirror the parameter merging done in StreamingChatModel.chat() so that
            // model defaults (including modelName) are present in the logged JSON
            var reqParams = request.parameters();
            var merged = openAiModel.defaultRequestParameters().overrideWith(reqParams);
            var mergedRequest = ChatRequest.builder()
                    .messages(request.messages())
                    .parameters(merged)
                    .build();
            var openAiRequest = OpenAiUtils.toOpenAiChatRequest(
                            mergedRequest, merged, openAiModel.strictTools(), openAiModel.strictJsonSchema())
                    .stream(true)
                    .streamOptions(StreamOptions.builder().includeUsage(true).build())
                    .build();
            try {
                return objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ChatCompletionRequest.builder()
                                .from(openAiRequest)
                                .build());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Actually performs one streaming call to the LLM, returning once the response is done or there's an error. Partial
     * tokens go to console if the `echo` field is true.
     */
    private StreamingResult doSingleStreamingCall(ChatRequest request) throws InterruptedException {
        int logSequence = logRequest(request);
        StreamingResult result;
        try {
            result = doSingleStreamingCallInternal(request);
        } catch (InterruptedException e) {
            logResult(model, request, null, logSequence);
            throw e;
        }
        logResult(model, request, result, logSequence);
        return result;
    }

    private StreamingResult doSingleStreamingCallInternal(ChatRequest request) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        // latch for awaiting any response from llm
        var tick = new Semaphore(0);
        var firstToken = new AtomicBoolean(true);
        var completed = new AtomicBoolean(false);
        var cancelled = new AtomicBoolean(false);
        var lock = new ReentrantLock(); // Used by ifNotCancelled

        var hasStartedNonReasoningOutput = new AtomicBoolean(false);
        var loggedReasoningAfterContent = new AtomicBoolean(false);

        // Variables to store results from callbacks
        var accumulatedTextBuilder = new StringBuilder();
        var accumulatedReasoningBuilder = new StringBuilder();
        var completedChatResponse = new AtomicReference<@Nullable ChatResponse>();
        var errorRef = new AtomicReference<@Nullable Exception>();
        long elapsedMs;

        Consumer<Runnable> ifNotCancelled = r -> {
            lock.lock();
            try {
                if (!cancelled.get()) {
                    r.run();
                }
            } catch (RuntimeException e) {
                // Recover from callback exceptions to prevent thread leakage/hangs
                logger.error(e);
                errorRef.set(e);
                completed.set(true);
                // Only report unexpected internal errors, not expected LLM/network errors
                if (!(e instanceof LangChain4jException)) {
                    contextManager.reportException(e);
                }
            } finally {
                if (firstToken.get()) {
                    firstToken.set(false);
                }
                // signal an event (partial, reasoning, complete, or error)
                tick.release();
                lock.unlock();
            }
        };
        var rawHandler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                ifNotCancelled.accept(() -> {
                    if (!token.isEmpty()) {
                        hasStartedNonReasoningOutput.set(true);
                    }
                    accumulatedTextBuilder.append(token);
                    if (echo) {
                        io.llmOutput(
                                token, ChatMessageType.AI, LlmOutputMeta.DEFAULT.withReasoning(forceReasoningEcho));
                    }
                });
            }

            @Override
            public void onReasoningResponse(String reasoningContent) {
                ifNotCancelled.accept(() -> {
                    if (hasStartedNonReasoningOutput.get()) {
                        if (loggedReasoningAfterContent.compareAndSet(false, true)) {
                            var svc = contextManager.getService();
                            var modelConfig = Service.ModelConfig.from(model, svc);
                            logger.warn(
                                    "Stream switched from non-reasoning to reasoning; ignoring reasoning tokens. modelConfig={}",
                                    modelConfig);
                        }
                        return;
                    }

                    // Gate formatting to GPT-5 (and other variants like mini) only, and only after the first reasoning
                    // chunk
                    boolean isGpt5 = contextManager.getService().nameOf(model).startsWith(ModelProperties.GPT_5);
                    String out = isGpt5 ? addReasoningNewlinesForGpt5(reasoningContent) : reasoningContent;

                    accumulatedReasoningBuilder.append(out);
                    if (echo) {
                        io.llmOutput(out, ChatMessageType.AI, LlmOutputMeta.reasoning());
                    }
                });
            }

            @Override
            public void onCompleteResponse(@Nullable ChatResponse response) {
                ifNotCancelled.accept(() -> {
                    if (response == null) {
                        if (completedChatResponse.get() != null) {
                            logger.debug("Got a null response from LC4J after a successful one!?");
                            // ignore the null response
                            return;
                        }
                        // I think this isn't supposed to happen, but seeing it when litellm throws back a 400.
                        // Fake an exception so the caller can treat it like other errors
                        var ex = new LitellmException(
                                "(no further information, the response was null; check litellm logs)");
                        logger.debug(ex);
                        errorRef.set(ex);
                    } else {
                        completedChatResponse.set(response);
                        String tokens =
                                response.tokenUsage() == null ? "null token usage!?" : formatTokensUsage(response);
                        logger.debug("Request complete ({}) with {}", response.finishReason(), tokens);
                    }
                    completed.set(true);
                });
            }

            @Override
            public void onError(Throwable th) {
                ifNotCancelled.accept(() -> {
                    logger.debug(th);
                    var mapped = ExceptionMapper.DEFAULT.mapException(th);
                    var retryable = !(mapped instanceof NonRetriableException);
                    // Immediate feedback for user
                    String message =
                            "LLM Error: " + mapped.getMessage() + (retryable ? " (retry-able)" : " (non-retriable)");
                    io.showNotification(IConsoleIO.NotificationRole.INFO, message);
                    errorRef.set(mapped);
                    completed.set(true);
                });
            }
        };

        var finalHandler =
                contextManager.getService().usesThinkTags(model) ? new ThinkTagInterceptor(rawHandler) : rawHandler;
        long startTime = System.currentTimeMillis();
        try {
            model.chat(request, finalHandler);
        } catch (Throwable t) {
            var mapped = ExceptionMapper.DEFAULT.mapException(t);
            lock.lock();
            try {
                cancelled.set(true);
                logger.debug(mapped);
                var retryable = !(mapped instanceof NonRetriableException);
                String message =
                        "LLM Error: " + mapped.getMessage() + (retryable ? " (retry-able)" : " (non-retriable)");
                io.showNotification(IConsoleIO.NotificationRole.INFO, message);
                errorRef.set(mapped);
                completed.set(true);
            } finally {
                lock.unlock();
                tick.release();
            }
        }

        try {
            while (!completed.get()) {
                long secs = getLlmResponseTimeoutSeconds(firstToken.get());
                boolean gotSignal = tick.tryAcquire(secs, TimeUnit.SECONDS);
                if (!gotSignal) {
                    lock.lock();
                    try {
                        cancelled.set(true);
                        errorRef.set(new HttpException(504, "LLM timed out"));
                        completed.set(true);
                    } finally {
                        lock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            lock.lock(); // LockNotBeforeTry
            try {
                cancelled.set(true); // Ensure callback stops echoing
            } finally {
                lock.unlock();
            }
            throw e; // Propagate interruption
        }

        // Record elapsed time
        long endTime = System.currentTimeMillis();
        elapsedMs = endTime - startTime;

        // At this point, latch has been counted down and we have a result or an error
        var error = errorRef.get();
        // Reload list of available models if user has exhausted his budget
        if (error instanceof PaymentRequiredException && contextManager instanceof ContextManager cm) {
            cm.reloadService();
        }

        if (error != null) {
            // If no partial text, just return null response
            var partialText = accumulatedTextBuilder.toString();
            var partialReasoning = accumulatedReasoningBuilder.toString();
            if (partialText.isEmpty() && partialReasoning.isEmpty()) {
                return new StreamingResult(null, error, 0, elapsedMs);
            }

            // Construct a ChatResponse from accumulated partial text
            var partialResponse = new NullSafeResponse(partialText, partialReasoning, List.of(), null);
            logger.debug(
                    "LLM call resulted in error: {}. Partial text captured: {} chars",
                    error.getMessage(),
                    partialText.length());
            return new StreamingResult(partialResponse, error, 0, elapsedMs);
        }

        // Happy path: successful completion, no errors
        var response = completedChatResponse.get(); // Will be null if an error occurred or onComplete got null
        assert response != null : "If no error, completedChatResponse must be set by onCompleteResponse";
        return StreamingResult.fromResponse(response, null, elapsedMs);
    }

    private long getLlmResponseTimeoutSeconds(boolean firstToken) {
        long firstTokenTimeoutSeconds = Service.getProcessingTier(model) == Service.ProcessingTier.FLEX
                ? Service.FLEX_FIRST_TOKEN_TIMEOUT_SECONDS
                : Service.DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS;
        return firstToken ? firstTokenTimeoutSeconds : Service.NEXT_TOKEN_TIMEOUT_SECONDS;
    }

    /**
     * GPT-5 reasoning formatting workaround: Insert two newlines before an opening "**" when we are mid-message (not
     * the first chunk). Regex matches "**" not preceded by a newline or word char, followed by a word char, and not
     * part of "***".
     */
    private static String addReasoningNewlinesForGpt5(String text) {
        if (!text.isEmpty()) {
            return text.replaceAll("(?<!\\n)(?<!\\w)\\*\\*(?=\\w)(?!\\*)", "\n\n**");
        }
        return text;
    }

    private static class LitellmException extends RetriableException {
        public LitellmException(String message) {
            super(message);
        }
    }

    public static class EmptyResponseException extends RetriableException {
        public EmptyResponseException() {
            super("Empty response from LLM");
        }
    }

    public static class MissingToolCallsException extends RetriableException {
        public MissingToolCallsException(int attemptsMade) {
            super("ToolChoice.REQUIRED could not be satisfied after " + attemptsMade + " attempt(s)");
        }
    }

    private static String formatTokensUsage(ChatResponse response) {
        var tu = (OpenAiTokenUsage) response.tokenUsage();
        var template = "token usage: %,d input (%s cached), %,d output (%s reasoning)";
        var inputDetails = tu.inputTokensDetails();
        var outputDetails = tu.outputTokensDetails();
        return template.formatted(
                tu.inputTokenCount(),
                (inputDetails == null || inputDetails.cachedTokens() == null)
                        ? "?"
                        : "%,d".formatted(inputDetails.cachedTokens()),
                tu.outputTokenCount(),
                (outputDetails == null || outputDetails.reasoningTokens() == null)
                        ? "?"
                        : "%,d".formatted(outputDetails.reasoningTokens()));
    }

    /**
     * Per-call options for a single sendRequest invocation. Controls structured output, tool usage, and retry count.
     * Build via the static factory methods or the constructor.
     */
    public record RequestOptions(@Nullable ResponseFormat responseFormat, ToolContext toolContext, int maxAttempts) {

        public static RequestOptions defaults(int maxAttempts) {
            return new RequestOptions(null, ToolContext.empty(), maxAttempts);
        }

        public RequestOptions withResponseFormat(@Nullable ResponseFormat responseFormat) {
            return new RequestOptions(responseFormat, toolContext, maxAttempts);
        }

        public RequestOptions withToolContext(ToolContext toolContext) {
            return new RequestOptions(responseFormat, toolContext, maxAttempts);
        }

        public RequestOptions withMaxAttempts(int maxAttempts) {
            return new RequestOptions(responseFormat, toolContext, maxAttempts);
        }
    }

    /**
     * Sends a user query to the LLM with streaming. Tools are not used. Writes to conversation history. Responses are
     * echoed to the console if the `echo` field is set to true.
     *
     * @param messages The messages to send
     * @return The final response from the LLM as a record containing ChatResponse, errors, etc.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages) throws InterruptedException {
        return sendRequest(messages, RequestOptions.defaults(MAX_ATTEMPTS));
    }

    /**
     * Sends messages to the model with a custom max retry count. Useful for non-critical tasks
     * like history compression where we don't want to block for too long on failures.
     *
     * @param messages The messages to send
     * @param maxAttempts Maximum number of attempts (1 = no retries)
     * @return The final response from the LLM
     */
    public StreamingResult sendRequest(List<ChatMessage> messages, int maxAttempts) throws InterruptedException {
        return sendRequest(messages, RequestOptions.defaults(maxAttempts));
    }

    /** Sends messages to a model with possible tools and a chosen tool usage policy. */
    public StreamingResult sendRequest(List<ChatMessage> messages, ToolContext toolContext)
            throws InterruptedException {
        return sendRequest(messages, RequestOptions.defaults(MAX_ATTEMPTS).withToolContext(toolContext));
    }

    /**
     * Canonical sendRequest entry point. All other overloads delegate here.
     * Supports per-call structured output via {@link RequestOptions#withResponseFormat}.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages, RequestOptions options) throws InterruptedException {
        var toolContext = options.toolContext();

        if (toolContext.toolSpecifications().isEmpty()) {
            return sendMessageWithRetry(messages, options);
        }

        int remainingAttempts = options.maxAttempts();
        int toolContractFailures = 0;
        int totalAttemptsUsed = 0;

        var attemptMessages = messages;

        while (true) {
            var rawResult = sendMessageWithRetry(attemptMessages, options.withMaxAttempts(remainingAttempts));
            int attemptsUsedThisRound = rawResult.retries() + 1;
            totalAttemptsUsed += attemptsUsedThisRound;
            remainingAttempts -= attemptsUsedThisRound;

            if (rawResult.error() != null) {
                return rawResult.withRetryCount(totalAttemptsUsed - 1);
            }

            var check = checkToolContractAndPostProcess(rawResult, toolContext);
            if (check.errors().isEmpty()) {
                var finalResult = check.processedResult();
                if (echo) {
                    prettyPrintToolCalls(toolContext, finalResult.toolRequests());
                }
                return new StreamingResult(
                        finalResult.chatResponse(), null, totalAttemptsUsed - 1, finalResult.elapsedMs());
            }

            boolean missingRequired = toolContext.toolChoice() == ToolChoice.REQUIRED
                    && check.processedResult().toolRequests().isEmpty();

            if (missingRequired) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Enforcing tool selection");
            }

            if (toolContractFailures >= MAX_TOOL_CONTRACT_RETRIES || remainingAttempts <= 0) {
                Exception ex = missingRequired
                        ? new MissingToolCallsException(totalAttemptsUsed)
                        : new IllegalArgumentException(
                                "Tool call validation failed: " + String.join("; ", check.errors()));
                return new StreamingResult(null, ex, totalAttemptsUsed - 1, rawResult.elapsedMs());
            }

            toolContractFailures++;

            if (echo) {
                io.llmOutput(
                        "\nTool contract errors:\n- " + String.join("\n- ", check.errors()),
                        ChatMessageType.CUSTOM,
                        LlmOutputMeta.DEFAULT.withReasoning(forceReasoningEcho));
            }

            // Create a new list for the next attempt. We replace the last user message
            // with a modified version that includes retry instructions to avoid message bloat.
            var lastMessage = messages.getLast();
            assert lastMessage instanceof UserMessage : "Expected last message to be a UserMessage";
            attemptMessages = new ArrayList<>(messages);
            String originalText = Messages.getText(lastMessage);
            String retryInstructions = buildRetryInstructions(check.errors());
            attemptMessages.set(attemptMessages.size() - 1, new UserMessage(originalText + "\n\n" + retryInstructions));
        }
    }

    private record ToolContractCheck(StreamingResult rawResult, StreamingResult processedResult, List<String> errors) {}

    private ToolContractCheck checkToolContractAndPostProcess(StreamingResult result, ToolContext toolContext) {
        var errors = new ArrayList<String>();
        var requests = result.toolRequests();
        var tr = toolContext.toolRegistry();

        if (toolContext.toolChoice() == ToolChoice.REQUIRED && requests.isEmpty()) {
            errors.add("At least one tool execution request is REQUIRED.");
        }

        for (var req : requests) {
            try {
                tr.validateTool(req);
            } catch (ToolRegistry.ToolValidationException e) {
                errors.add("%s: %s".formatted(req.name(), e.getMessage()));
            }
        }

        return new ToolContractCheck(result, result, List.copyOf(errors));
    }

    private String buildRetryInstructions(List<String> errors) {
        var errorText = "- " + String.join("\n- ", errors);

        return "[HARNESS NOTE: I am retrying this turn; your previous response requested tool calls that failed as follows:\n%s\nPlease check the tool specifications carefully and issue corrected requests.]"
                .formatted(errorText);
    }

    /**
     * Retries a request up to maxAttempts times on connectivity or empty-result errors, using exponential backoff.
     * Responsible for writeToHistory.
     */
    private StreamingResult sendMessageWithRetry(List<ChatMessage> rawMessages, RequestOptions options)
            throws InterruptedException {
        int maxAttempts = options.maxAttempts();
        if (SwingUtilities.isEventDispatchThread() && Boolean.getBoolean("brokk.devmode")) {
            throw new IllegalStateException("LLM calls must not be made from the EDT");
        }
        if (options.toolContext().toolChoice().equals(ToolChoice.REQUIRED)
                && options.toolContext().toolSpecifications().isEmpty()) {
            throw new IllegalArgumentException("REQUIRED tool specifications must not be empty");
        }

        @Nullable Exception lastError = null;
        int attempt = 0;
        var messages = Messages.forLlm(rawMessages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Cannot send request with empty message list");
        }

        StreamingResult response;
        while (attempt++ < maxAttempts) {
            String description = Messages.getText(messages.getLast());
            logger.debug(
                    "Sending request to {} attempt {}: {}",
                    contextManager.getService().nameOf(model),
                    attempt,
                    LogDescription.getShortDescription(description, 12));

            response = doSingleSendMessage(model, messages, options);
            lastError = response.error;
            if (!response.isEmpty() && (lastError == null || allowPartialResponses)) {
                // Success!
                return response.withRetryCount(attempt - 1);
            }

            // don't retry on non-retriable errors or known bad request errors
            if (lastError instanceof NonRetriableException) {
                break;
            }

            logger.debug("LLM error == {}, isEmpty == {}. Attempt={}", lastError, response.isEmpty(), attempt);
            if (attempt == maxAttempts) {
                break; // done
            }
            // wait between attempts
            long backoffSeconds = 1L << (attempt - 1);
            backoffSeconds = Math.min(backoffSeconds, 16L);

            // Throttled countdown notifications (every ~2s)
            if (backoffSeconds > 1) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        String.format(
                                "LLM issue on attempt %d/%d (retrying in %d seconds).",
                                attempt, maxAttempts, backoffSeconds));
            } else {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        String.format("LLM issue on attempt %d/%d (retrying).", attempt, maxAttempts));
            }

            long endTime = System.currentTimeMillis() + backoffSeconds * 1000L;
            long nextNotifyAt = System.currentTimeMillis() + 2000L; // notify at most every 2 seconds
            while (true) {
                long now = System.currentTimeMillis();
                long remain = endTime - now;
                if (remain <= 0) {
                    break;
                }

                if (backoffSeconds > 1 && now >= nextNotifyAt) {
                    long secsLeft = (long) Math.ceil(remain / 1000.0);
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO, "Retrying in %d seconds...".formatted(secsLeft));
                    nextNotifyAt = now + 2000L;
                }

                // short sleep to remain responsive to interruption while avoiding busy-wait
                Thread.sleep(Math.min(remain, 200));
            }
        }

        // If we get here, we failed all attempts
        if (lastError == null) {
            return new StreamingResult(null, new EmptyResponseException(), attempt - 1);
        }
        return new StreamingResult(null, lastError, attempt - 1);
    }

    /**
     * Sends messages to model in a single attempt.
     */
    private StreamingResult doSingleSendMessage(
            StreamingChatModel model, List<ChatMessage> messages, RequestOptions options) throws InterruptedException {
        // Note: writeRequestToHistory is now called *within* this method,
        // right before doSingleStreamingCall, to ensure it uses the final `messagesToSend`.

        var toolContext = options.toolContext();
        var tools = toolContext.toolSpecifications();
        var toolChoice = toolContext.toolChoice();

        // Build request with parameters (always include base params for previousResponseId/metadata)
        var requestBuilder = ChatRequest.builder().messages(messages);
        var paramsBuilder = getParamsBuilder(options);

        if (!tools.isEmpty()) {
            logger.debug("Performing native tool calls");
            paramsBuilder = paramsBuilder.toolSpecifications(tools);
            if (contextManager.getService().supportsParallelCalls(model)) {
                // can't just blindly call .parallelToolCalls(boolean), litellm will barf if it sees the option at all
                paramsBuilder = paramsBuilder.parallelToolCalls(true);
            }
            if (toolChoice == ToolChoice.REQUIRED && contextManager.getService().supportsToolChoiceRequired(model)) {
                paramsBuilder = paramsBuilder.toolChoice(ToolChoice.REQUIRED);
            }
        }

        var request = requestBuilder.parameters(paramsBuilder.build()).build();
        return doSingleStreamingCall(request);
    }

    private void prettyPrintToolCalls(ToolContext toolContext, List<ToolExecutionRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        var tr = toolContext.toolRegistry();

        // we need an empty line before each tool call render (needed for rehype-plugin to render correctly)
        var rendered = requests.stream()
                .map(tr::getExplanationForToolRequest)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        if (!rendered.isBlank()) {
            io.llmOutput(
                    "\n\n" + rendered, ChatMessageType.AI, LlmOutputMeta.DEFAULT.withReasoning(forceReasoningEcho));
        }
    }

    private OpenAiChatRequestParameters.Builder getParamsBuilder(RequestOptions options) {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder();

        // user opted in to data retention for this request
        if (tagRetain) {
            // this is the only place we add metadata so we can just overwrite what's there
            logger.trace("Adding 'retain' metadata tag to LLM request.");
            Map<String, String> newMetadata = new HashMap<>();
            newMetadata.put("tags", "retain");
            builder.metadata(newMetadata);
        }

        if (options.responseFormat() != null) {
            builder.responseFormat(options.responseFormat());
        }

        return builder;
    }

    /**
     * Writes response history (.log) to task-specific files, pairing with pre-sent request JSON via a shared base path.
     */
    private synchronized void logResult(
            StreamingChatModel model, ChatRequest request, @Nullable StreamingResult result, int logSequence) {
        try {
            var dir = getOrCreateTaskHistoryDir();
            var formattedRequest = "# Request to %s:\n\n%s\n"
                    .formatted(contextManager.getService().nameOf(model), Messages.format(request.messages()));
            var formattedTools = request.toolSpecifications() == null
                            || request.toolSpecifications().isEmpty()
                    ? ""
                    : "# Tools:\n\n"
                            + request.toolSpecifications().stream()
                                    .map(ToolSpecification::name)
                                    .collect(Collectors.joining("\n"));
            var formattedResponse =
                    result == null ? "# Response:\n\nCancelled" : "# Response:\n\n%s".formatted(result.formatted());

            String fileTimestamp = logFileTimestamp();
            String shortDesc =
                    result == null ? "Cancelled" : LogDescription.getShortDescription(result.getDescription());
            var filePath = dir.resolve(String.format("%s %03d-%s.log", fileTimestamp, logSequence, shortDesc));
            var options = new StandardOpenOption[] {
                StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
            };
            logger.trace("Writing history to file {}", filePath);
            Files.writeString(
                    filePath, formattedRequest + "\n\n" + formattedTools + "\n\n" + formattedResponse, options);
        } catch (IOException e) {
            logger.error("Failed to write LLM response history file", e);
        }

        // Compute and show cost notification if usage/pricing are available
        if (result != null) {
            var usage = result.metadata();
            if (usage != null) {
                var service = contextManager.getService();
                var modelName = service.nameOf(model);
                // Filter out cost notifications for free-tier models unless explicitly enabled
                if (service.isFreeTier(modelName) && !GlobalUiSettings.isShowFreeInternalLLMCostNotifications()) {
                    logger.debug(
                            "Skipping cost notification for {} (user preference for Free Internal LLM logging)",
                            modelName);
                    return;
                }
                // Respect user preference for cost notifications
                if (!GlobalUiSettings.isShowCostNotifications()) {
                    logger.debug("Cost notifications disabled by user settings");
                    return;
                }
                var tier = Service.getProcessingTier(model);
                var pricing = service.getModelPricing(modelName, tier);

                int input = usage.inputTokens();
                int cached = usage.cachedInputTokens();
                int uncached = Math.max(0, input - cached);
                int output = usage.outputTokens();

                int totalTokens = Math.max(0, input) + Math.max(0, output);
                int cachedPct = input > 0 ? (int) Math.round((cached * 100.0) / input) : 0;
                String tokenSummary = "%,d tokens / %d%% cached".formatted(totalTokens, cachedPct);

                if (pricing.bands().isEmpty()) {
                    String message = "Cost unknown for %s (%s)".formatted(modelName, tokenSummary);
                    io.showNotification(IConsoleIO.NotificationRole.COST, message);
                    logger.debug("LLM cost: {}", message);
                } else {
                    double cost = pricing.getCostFor(uncached, cached, output);
                    DecimalFormat df = (DecimalFormat) NumberFormat.getNumberInstance(Locale.US);
                    df.applyPattern("#,##0.0000");
                    String costStr = df.format(cost);
                    String message = "$" + costStr + " for " + modelName + " (" + tokenSummary + ")";
                    io.showNotification(IConsoleIO.NotificationRole.COST, message, cost);
                    logger.debug("LLM cost: {}", message);
                }
            }
        }
    }

    public record NullSafeResponse(
            @Nullable String text,
            @Nullable String reasoningContent,
            List<ToolExecutionRequest> toolRequests,
            @Nullable ChatResponse originalResponse) {
        public NullSafeResponse(@Nullable ChatResponse cr) {
            this(
                    cr == null || cr.aiMessage() == null ? null : cr.aiMessage().text(),
                    cr == null || cr.aiMessage() == null ? null : cr.aiMessage().reasoningContent(),
                    cr == null || cr.aiMessage() == null || !cr.aiMessage().hasToolExecutionRequests()
                            ? List.of()
                            : cr.aiMessage().toolExecutionRequests(),
                    cr);
        }

        public boolean isEmpty() {
            var emptyText = text == null || text.isEmpty();
            return emptyText && toolRequests.isEmpty();
        }

        public AiMessage aiMessage() {
            var thoughtSignature = originalResponse == null
                    ? null
                    : originalResponse.aiMessage().thoughtSignature();
            return new AiMessage(text, reasoningContent, thoughtSignature, toolRequests);
        }
    }

    public void setOutput(IConsoleIO io) {
        // TODO this should be final but disentangling from ContextManager is difficult
        this.io = io;
    }

    public record ResponseMetadata(
            int inputTokens,
            int cachedInputTokens,
            int thinkingTokens,
            int outputTokens,
            long elapsedMs,
            @Nullable String modelName,
            @Nullable String finishReason,
            @Nullable String created,
            @Nullable String serviceTier,
            @Nullable String error) {
        /**
         * Combines two ResponseMetadata objects by summing their token counts and elapsed time.
         * Handles null values: returns the other metadata if one is null.
         * Note: categorical fields (modelName, etc.) are taken from the second operand if present.
         */
        public static @Nullable ResponseMetadata sum(@Nullable ResponseMetadata a, @Nullable ResponseMetadata b) {
            if (a == null) return b;
            if (b == null) return a;

            // Track which fields overflowed so we emit a single log entry if any overflow occurs.
            List<String> overflowedFields = new ArrayList<>();

            int inputTokens;
            try {
                inputTokens = Math.addExact(a.inputTokens(), b.inputTokens());
            } catch (ArithmeticException e) {
                inputTokens = Integer.MAX_VALUE;
                overflowedFields.add("inputTokens");
            }

            int cachedInputTokens;
            try {
                cachedInputTokens = Math.addExact(a.cachedInputTokens(), b.cachedInputTokens());
            } catch (ArithmeticException e) {
                cachedInputTokens = Integer.MAX_VALUE;
                overflowedFields.add("cachedInputTokens");
            }

            int thinkingTokens;
            try {
                thinkingTokens = Math.addExact(a.thinkingTokens(), b.thinkingTokens());
            } catch (ArithmeticException e) {
                thinkingTokens = Integer.MAX_VALUE;
                overflowedFields.add("thinkingTokens");
            }

            int outputTokens;
            try {
                outputTokens = Math.addExact(a.outputTokens(), b.outputTokens());
            } catch (ArithmeticException e) {
                outputTokens = Integer.MAX_VALUE;
                overflowedFields.add("outputTokens");
            }

            long elapsedMs;
            try {
                elapsedMs = Math.addExact(a.elapsedMs(), b.elapsedMs());
            } catch (ArithmeticException e) {
                elapsedMs = Long.MAX_VALUE;
                overflowedFields.add("elapsedMs");
            }

            if (!overflowedFields.isEmpty()) {
                // Summarize the overflow event in a single log entry to avoid per-field log spam.
                // Include the two source ResponseMetadata instances for context (their toString shows fields).
                logger.warn(
                        "Overflow summing ResponseMetadata for fields: {}. Values: a={}, b={}. Results clamped where necessary.",
                        String.join(", ", overflowedFields),
                        a,
                        b);
            }

            // Keep categorical-field behavior unchanged: prefer non-null values from b, falling back to a.
            return new ResponseMetadata(
                    inputTokens,
                    cachedInputTokens,
                    thinkingTokens,
                    outputTokens,
                    elapsedMs,
                    b.modelName() != null ? b.modelName() : a.modelName(),
                    b.finishReason() != null ? b.finishReason() : a.finishReason(),
                    b.created() != null ? b.created() : a.created(),
                    b.serviceTier() != null ? b.serviceTier() : a.serviceTier(),
                    b.error() != null ? b.error() : a.error());
        }
    }

    /**
     * The result of a streaming call. Exactly one of (chatResponse, error) is not null UNLESS if the LLM hangs up
     * abruptly after starting its response. In that case we'll forge a NullSafeResponse with the partial result and
     * also include the error that we got from the HTTP layer. In this case chatResponse and error will both be
     * non-null, but chatResponse.originalResponse will be null.
     *
     * <p>Generally, callers should use the helper methods isEmpty, isPartial, etc. instead of manually inspecting the
     * contents of chatResponse.
     */
    public record StreamingResult(
            @Nullable NullSafeResponse chatResponse, @Nullable Exception error, int retries, long elapsedMs) {
        public StreamingResult(@Nullable NullSafeResponse partialResponse, @Nullable Exception error, int retries) {
            this(partialResponse, error, retries, 0);
        }

        public static StreamingResult fromResponse(
                @Nullable ChatResponse originalResponse, @Nullable Exception error, long elapsedMs) {
            NullSafeResponse nsr = new NullSafeResponse(originalResponse);
            if (error == null && originalResponse != null) {
                boolean isLength = originalResponse.finishReason() == FinishReason.LENGTH;
                if (isLength && nsr.isEmpty()) {
                    error = new OverthinkingException("Model reached max output tokens before generating text");
                    // If we set error, we must ensure NullSafeResponse doesn't have the originalResponse
                    // to satisfy the constructor assertion.
                    nsr = new NullSafeResponse(nsr.text(), nsr.reasoningContent(), nsr.toolRequests(), null);
                }
            }
            return new StreamingResult(nsr, error, 0, elapsedMs);
        }

        public StreamingResult {
            if (error == null) {
                // If there's no error, we must have a chatResponse.
                assert chatResponse != null;
            } else {
                // If there is an error, chatResponse may or may not be present.
                // If chatResponse IS present, its originalResponse MUST be null,
                // indicating it's a partial/synthetic response accompanying an error.
                assert chatResponse == null || chatResponse.originalResponse == null;
            }
        }

        public @Nullable ResponseMetadata metadata() {
            var response = originalResponse();
            if (response == null) {
                return error == null
                        ? null
                        : new ResponseMetadata(0, 0, 0, 0, elapsedMs, null, null, null, null, error.getMessage());
            }
            var usage = (OpenAiTokenUsage) response.tokenUsage();
            if (usage == null) {
                logger.warn("Response is present but tokenUsage is null. Litellm bug?");
                return null;
            }

            // always present
            int inputTokens = usage.inputTokenCount();
            int cachedInputTokens = 0;
            int thinkingTokens = 0;
            int outputTokens = usage.outputTokenCount();

            // only present if litellm didn't fuck up the streaming-complete response
            var inputDetails = usage.inputTokensDetails();
            var outputDetails = usage.outputTokensDetails();
            if (inputDetails != null && inputDetails.cachedTokens() != null) {
                cachedInputTokens = inputDetails.cachedTokens();
            }
            if (outputDetails != null && outputDetails.reasoningTokens() != null) {
                thinkingTokens = outputDetails.reasoningTokens();
            }

            String modelName = response.metadata().modelName();
            String finishReason = response.finishReason() == null
                    ? null
                    : response.finishReason().name();
            String created = null;
            String serviceTier = null;

            if (response.metadata() instanceof dev.langchain4j.model.openai.OpenAiChatResponseMetadata meta) {
                if (meta.created() != null) {
                    created = LocalDateTime.ofInstant(
                                    java.time.Instant.ofEpochSecond(meta.created()), ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
                serviceTier =
                        meta.serviceTier() == null ? null : meta.serviceTier().name();
            }

            return new ResponseMetadata(
                    inputTokens,
                    cachedInputTokens,
                    thinkingTokens,
                    outputTokens,
                    elapsedMs,
                    modelName,
                    finishReason,
                    created,
                    serviceTier,
                    error == null ? null : error.getMessage());
        }

        public String text() {
            if (chatResponse == null) {
                return "";
            }
            var text = chatResponse.text();
            return text == null ? "" : text;
        }

        public boolean isEmpty() {
            return chatResponse == null || chatResponse.isEmpty();
        }

        public @Nullable ChatResponse originalResponse() {
            return chatResponse == null ? null : chatResponse.originalResponse;
        }

        public List<ToolExecutionRequest> toolRequests() {
            return chatResponse == null ? List.of() : chatResponse.toolRequests;
        }

        /** Package-private since unless you are test code you should almost always call aiMessage() instead */
        @VisibleForTesting
        AiMessage originalMessage() {
            return requireNonNull(requireNonNull(chatResponse).originalResponse).aiMessage();
        }

        public boolean isPartial() {
            if (error == null) {
                return castNonNull(originalResponse()).finishReason() == FinishReason.LENGTH;
            }
            return chatResponse != null;
        }

        /** @return the response text if a response is present; else throws */
        public AiMessage aiMessage() {
            requireNonNull(chatResponse);
            return chatResponse.aiMessage();
        }

        public String formatted() {
            if (error != null) {
                String contentToShow;
                // text() helper method returns chatResponse.text() or "" if chatResponse is null.
                if (!text().isEmpty()) {
                    contentToShow = "[Partial response text]\n" + text();
                } else {
                    contentToShow = "[No response content available]";
                }
                return """
                       [Error: %s]
                       %s
                       """
                        .formatted(ExceptionReporter.formatStackTrace(error), contentToShow);
            }

            AiMessage ai = aiMessage();
            String toolRequestsJson = "[]";
            String metadataJson = "{}";

            try {
                toolRequestsJson =
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ai.toolExecutionRequests());
                Map<String, Object> metadata = new HashMap<>();
                var meta = metadata();
                if (meta != null) {
                    metadata.put("inputTokens", meta.inputTokens());
                    metadata.put("cachedInputTokens", meta.cachedInputTokens());
                    metadata.put("thinkingTokens", meta.thinkingTokens());
                    metadata.put("outputTokens", meta.outputTokens());
                    if (meta.modelName() != null) metadata.put("modelName", meta.modelName());
                    if (meta.finishReason() != null) metadata.put("finishReason", meta.finishReason());
                    if (meta.created() != null) metadata.put("created", meta.created());
                    if (meta.serviceTier() != null) metadata.put("serviceTier", meta.serviceTier());
                }
                metadata.put("elapsedMs", elapsedMs);
                metadataJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize components for formatted()", e);
            }

            return """
                ## reasoningContent
                %s

                ## text
                %s

                ## toolExecutionRequests
                %s

                ## metadata
                %s
                """
                    .formatted(
                            ai.reasoningContent() == null ? "" : ai.reasoningContent(),
                            ai.text() == null ? "" : ai.text(),
                            toolRequestsJson,
                            metadataJson);
        }

        /**
         * Generates a short description of the result for logging purposes.
         *
         * @return A short description string.
         */
        public String getDescription() {
            if (error != null) {
                return requireNonNullElse(error.getMessage(), error.toString());
            }

            var cr = castNonNull(chatResponse);
            if (!cr.toolRequests().isEmpty()) {
                return cr.toolRequests().stream()
                        .map(ToolExecutionRequest::name)
                        .collect(Collectors.joining(", "));
            }
            var text = cr.text();
            if (text == null || text.isBlank()) {
                return "[empty response]";
            }

            return text;
        }

        public StreamingResult withRetryCount(int retries) {
            return new StreamingResult(chatResponse, error, retries, elapsedMs);
        }
    }

    // FIXME nobody should be using this, you can call copy() instead if you want to create another LLM,
    // or pass the model instance instead of Llm
    public StreamingChatModel getModel() {
        return model;
    }

    public Llm copy(String newTaskDescription) {
        return new Llm(
                model,
                newTaskDescription,
                taskType,
                contextManager,
                allowPartialResponses,
                forceReasoningEcho,
                tagRetain,
                echo);
    }

    public void setModel(StreamingChatModel model) {
        this.model = model;
    }

    @Override
    public String toString() {
        return "LLM[" + model.provider().toString() + "]";
    }
}
