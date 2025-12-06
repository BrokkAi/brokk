package ai.brokk.gui;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Llm;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.Messages;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper that encapsulates the tiered logic for inferring an AGENTS.md file from a directory.
 *
 * <p>The decision uses token estimates and the selected model's input token budget:
 * - DIRECT_SUMMARIZE: raw file contents fit in the model context (with a safety margin)
 * - SUMMARIZE_THEN_SEARCHAGENT: raw contents don't fit, but estimated summaries will fit
 * - SEARCHAGENT_WITH_FILE_LIST: even summaries are too large; fall back to SearchAgent with file list
 *
 * <p>This class implements lightweight helpers for:
 * - collecting shallow (non-recursive) files from a directory as ProjectFile instances
 * - estimating token counts for the raw file contents using {@link Messages#getApproximateTokens(Collection)}
 * - querying the model input budget via {@link AbstractService#getMaxInputTokens(StreamingChatModel)}
 * - deciding which tier to use
 */
public class AgentsMdInferrer {
    private static final Logger logger = LogManager.getLogger(AgentsMdInferrer.class);

    public enum Tier {
        DIRECT_SUMMARIZE,
        SUMMARIZE_THEN_SEARCHAGENT,
        SEARCHAGENT_WITH_FILE_LIST
    }

    private final Path targetDirectory;
    private final ContextManager contextManager;
    private final Chrome chrome;
    private final StreamingChatModel model;

    public AgentsMdInferrer(Path targetDirectory, ContextManager contextManager, Chrome chrome, StreamingChatModel model) {
        this.targetDirectory = targetDirectory;
        this.contextManager = contextManager;
        this.chrome = chrome;
        this.model = model;
    }

    /**
     * Collects shallow (non-recursive) files under the target directory and converts them to ProjectFile instances.
     * Directories are ignored; only regular files are returned.
     */
    public List<ProjectFile> collectShallowFiles() {
        try (Stream<Path> stream = Files.list(targetDirectory)) {
            List<Path> filePaths = stream.filter(Files::isRegularFile).collect(Collectors.toList());
            var projectRoot = contextManager.getProject().getRoot();
            List<ProjectFile> result = new ArrayList<>();
            for (Path p : filePaths) {
                try {
                    Path rel = projectRoot.relativize(p);
                    result.add(new ProjectFile(projectRoot, rel));
                } catch (Exception ex) {
                    // If relative path creation fails (e.g., dropped external folder), fallback to best-effort:
                    logger.debug("Skipping non-project path while collecting shallow files for AGENTS.md: {}", p, ex);
                }
            }
            return List.copyOf(result);
        } catch (IOException ex) {
            logger.error("Failed to list files in directory for AGENTS.md inference: {}", targetDirectory, ex);
            return List.of();
        }
    }

    /**
     * Estimates total input tokens for the provided files by reading their content (when available)
     * and delegating token estimation to {@link Messages#getApproximateTokens(Collection)}.
     *
     * If a file cannot be read, it is treated as contributing zero tokens to the total.
     */
    public long estimateTokensForFiles(List<ProjectFile> files) {
        List<String> texts = new ArrayList<>();
        for (ProjectFile pf : files) {
            try {
                Optional<String> maybe = pf.read();
                maybe.ifPresent(texts::add);
            } catch (Exception ex) {
                logger.debug("Error reading project file for token estimation: {}", pf, ex);
            }
        }
        if (texts.isEmpty()) return 0L;
        return Messages.getApproximateTokens(texts);
    }

    /**
     * Queries the current service for the selected model's maximum input tokens.
     * Returns a conservative non-zero default if the service reports an unexpected value.
     */
    public int getModelMaxInputTokens() {
        try {
            AbstractService svc = contextManager.getService();
            int max = svc.getMaxInputTokens(model);
            if (max <= 0) {
                // Defensive fallback
                return 4096;
            }
            return max;
        } catch (Exception ex) {
            logger.warn("Could not determine model max input tokens, using fallback", ex);
            return 4096;
        }
    }

    /**
     * Determines which tier should be used for AGENTS.md inference for the currently configured directory
     * and model.
     *
     * Heuristic:
     * - Reserve a safety margin (10%) for prompt overhead: budget = floor(maxInputTokens * 0.9)
     * - If raw total tokens <= budget -> DIRECT_SUMMARIZE
     * - Else estimate summary size (heuristic: max(100, total/5)) and if that <= budget -> SUMMARIZE_THEN_SEARCHAGENT
     * - Otherwise -> SEARCHAGENT_WITH_FILE_LIST
     */
    public Tier determineTier() {
        List<ProjectFile> files = collectShallowFiles();
        long totalTokens = estimateTokensForFiles(files);
        int maxInput = getModelMaxInputTokens();

        // Safety budget leaves room for prompts, tool metadata, etc.
        int safetyBudget = (int) Math.floor(maxInput * 0.9);

        long estimatedSummariesTokens = estimateSummaryTokensHeuristic(totalTokens);

        logger.debug(
                "AGENTS.md tier decision: files={}, totalTokens={}, estimatedSummariesTokens={}, modelMax={}, safetyBudget={}",
                files.size(), totalTokens, estimatedSummariesTokens, maxInput, safetyBudget);

        return decideTierByCounts(totalTokens, estimatedSummariesTokens, safetyBudget);
    }

    /**
     * Heuristic to estimate the token size of summaries produced from the files.
     * Default: 20% of original tokens, with a minimum floor.
     */
    private static long estimateSummaryTokensHeuristic(long totalTokens) {
        if (totalTokens <= 0) return 0L;
        long est = Math.max(100L, totalTokens / 5L); // ~20%
        return est;
    }

    /**
     * Public static utility used by unit tests: decide tier using numeric token counts and an available budget.
     *
     * @param totalTokens          total tokens of raw files
     * @param summariesTokens      estimated tokens of summaries
     * @param safetyBudget         the input token budget available for the operation (already accounting for prompt overhead)
     */
    public static Tier decideTierByCounts(long totalTokens, long summariesTokens, int safetyBudget) {
        if (totalTokens <= safetyBudget) {
            return Tier.DIRECT_SUMMARIZE;
        }
        if (summariesTokens <= safetyBudget) {
            return Tier.SUMMARIZE_THEN_SEARCHAGENT;
        }
        return Tier.SEARCHAGENT_WITH_FILE_LIST;
    }

    /**
     * If the raw token total for the shallow files in the target directory is less than half of the model's
     * maximum input tokens, generate AGENTS.md directly using the LLM and write it to the directory as
     * AGENTS.md. Returns true on success, false otherwise.
     *
     * This method performs blocking I/O and a network call to the model. Annotated as blocking. It is the
     * caller's responsibility to avoid calling this on the EDT.
     */
    @org.jetbrains.annotations.Blocking
    public boolean generateAgentsMdIfTier1() {
        List<ProjectFile> files = collectShallowFiles();
        long totalTokens = estimateTokensForFiles(files);
        int maxInput = getModelMaxInputTokens();

        if (totalTokens <= 0) {
            logger.warn("No readable files found for AGENTS.md generation in {}", targetDirectory);
            return false;
        }
        if (maxInput <= 0) {
            logger.warn("Invalid model max input tokens ({}). Aborting AGENTS.md generation.", maxInput);
            return false;
        }

        // Use half the context window as the threshold for "direct summarization" (Tier 1)
        int threshold = Math.max(1, maxInput / 2);
        if (totalTokens >= threshold) {
            logger.debug("Total tokens ({}) >= half model context ({}). Skipping direct LLM summarization.", totalTokens, threshold);
            return false;
        }

        // Build the prompt. Keep the top-level instruction short and attach raw file contents.
        StringBuilder userBuilder = new StringBuilder();
        userBuilder.append("Produce a well-structured AGENTS.md in Markdown that summarizes the most important APIs and any subtle points developers should know.\n");
        userBuilder.append("Be concise but include examples or usage notes where helpful. Output only the markdown content appropriate for AGENTS.md.\n\n");

        for (ProjectFile pf : files) {
            try {
                Optional<String> maybeText = pf.read();
                if (maybeText.isPresent()) {
                    String content = maybeText.get();
                    // Include a filename marker and the raw file content
                    userBuilder.append("Filename: ").append(pf.toString()).append("\n");
                    userBuilder.append("```").append("\n");
                    userBuilder.append(content).append("\n");
                    userBuilder.append("```").append("\n\n");
                } else {
                    logger.debug("Skipping unreadable/empty file for prompt: {}", pf);
                }
            } catch (Exception ex) {
                logger.debug("Failed to read file while building AGENTS.md prompt: {}", pf, ex);
            }
        }

        // Prepare messages for the LLM
        List<ChatMessage> messages = List.of(
                Messages.create("You are a helpful assistant that generates project documentation.", ChatMessageType.SYSTEM),
                Messages.create(userBuilder.toString(), ChatMessageType.USER)
        );

        String taskDescription = "Generate AGENTS.md for " + targetDirectory.getFileName();

        Llm llm;
        try {
            llm = contextManager.getLlm(model, taskDescription);
            if (llm == null) {
                logger.warn("ContextManager did not provide an Llm instance for model; aborting AGENTS.md generation.");
                return false;
            }
            // Direct LLM call
            Llm.StreamingResult result = llm.sendRequest(messages);
            if (result == null || result.isEmpty() || result.chatResponse() == null) {
                logger.warn("LLM returned empty response when generating AGENTS.md for {}", targetDirectory);
                return false;
            }
            String output = result.text();
            if (output == null || output.isBlank()) {
                logger.warn("LLM produced blank AGENTS.md content for {}", targetDirectory);
                return false;
            }

            Path out = targetDirectory.resolve("AGENTS.md");
            try {
                Files.writeString(out, output, StandardCharsets.UTF_8);
                logger.info("Wrote AGENTS.md to {}", out);
                return true;
            } catch (IOException ioEx) {
                logger.error("Failed to write AGENTS.md to {}: {}", out, ioEx.getMessage(), ioEx);
                return false;
            }
        } catch (Exception ex) {
            logger.error("Failed to generate AGENTS.md using LLM for {}: {}", targetDirectory, ex.getMessage(), ex);
            return false;
        }
    }
}
