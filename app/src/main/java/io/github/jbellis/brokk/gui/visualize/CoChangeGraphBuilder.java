package io.github.jbellis.brokk.gui.visualize;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.util.ExecutorServiceUtil;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a co-change graph for project files by scanning commit history.
 * Initial skeleton: reports progress and returns an empty graph.
 */
public class CoChangeGraphBuilder {
    private static final Logger logger = LogManager.getLogger(CoChangeGraphBuilder.class);

    /** Simple progress payload for async reporting. */
    public record Progress(String message, int value, int max) {}

    private final IProject project;
    private final IGitRepo repo;

    public CoChangeGraphBuilder(Chrome chrome) {
        this.project = chrome.getProject();
        this.repo = project.getRepo();
        logger.debug("CoChangeGraphBuilder initialized for project {}", project.getRoot());
    }

    /**
     * Builds the co-change graph asynchronously.
     *
     * @param progressConsumer receives progress updates
     * @param isCancelled returns true to cancel
     * @param branch the branch to analyze; if null/blank, uses current branch
     * @param compareToBranch optional comparison branch (unused in skeleton)
     * @return a future that completes with an empty Graph for now
     */
    public CompletableFuture<Graph> buildAsync(
            Consumer<Progress> progressConsumer,
            Supplier<Boolean> isCancelled,
            @Nullable String branch,
            @Nullable String compareToBranch) {

        var maxConcurrency = Math.max(1, Runtime.getRuntime().availableProcessors());
        var executor = ExecutorServiceUtil.newVirtualThreadExecutor("cochange-vt-", maxConcurrency);

        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        if (isCancelled.get()) {
                            throw new CancellationException("Cancelled before start");
                        }

                        // Determine branch name
                        var branchName = (branch == null || branch.isBlank()) ? repo.getCurrentBranch() : branch;
                        logger.debug("Starting co-change scan on branch='{}', compareTo='{}'", branchName, compareToBranch);

                        // Require GitRepo for detailed commit listing
                        if (!(repo instanceof GitRepo gitRepo)) {
                            logger.warn("Project repo is not a GitRepo instance; returning empty graph");
                            progressConsumer.accept(new Progress("Unsupported repository type", 0, 0));
                            return new Graph(Map.of(), Map.of());
                        }

                        var commits = gitRepo.listCommitsDetailed(branchName);
                        int max = commits.size();
                        var initialMsg = "Scanning commits on " + branchName
                                + ((compareToBranch != null && !compareToBranch.isBlank()) ? " vs " + compareToBranch : "");
                        progressConsumer.accept(new Progress(initialMsg, 0, max));

                        int i = 0;
                        for (var ignored : commits) {
                            if (isCancelled.get()) {
                                throw new CancellationException("Cancelled during commit scan");
                            }
                            i++;
                            if ((i % 10) == 0 || i == max) {
                                progressConsumer.accept(new Progress("Scanning commits on " + branchName, i, max));
                            }
                        }

                        // Skeleton: return empty graph
                        return new Graph(Map.of(), Map.of());
                    } catch (GitAPIException e) {
                        logger.error("Failed to build co-change graph: {}", e.getMessage(), e);
                        throw new CompletionException(e);
                    }
                }, executor)
                .whenComplete((res, ex) -> executor.shutdown());
    }
}
