package ai.brokk.gui;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.Messages;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
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
}
