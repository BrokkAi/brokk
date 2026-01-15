package ai.brokk.agents;

import ai.brokk.IContextManager;
import ai.brokk.SessionManager;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.DiffService;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;

public record ReviewScope(DiffService.CumulativeChanges changes, List<UUID> sessionIds) {
    private static final Logger logger = LogManager.getLogger(ReviewAgent.class);

    /**
     * returns a ReviewContext for changes from ($leftRef..HEAD]
     */
    @Blocking
    public static ReviewScope fromBaseline(IContextManager cm, String leftRef) {
        var repo = (GitRepo) cm.getProject().getRepo();

        List<CommitInfo> commits = List.of();
        try {
            if (!"HEAD".equals(leftRef)) {
                commits = repo.listCommitsBetweenBranches(leftRef, "HEAD", false);
            }
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        var summarizedChanges = DiffService.computeCumulativeDiff(repo, leftRef, "WORKING", commits);

        GitWorkflow.PushPullState pushPullState = null;
        try {
            String currentBranch = repo.getCurrentBranch();
            boolean hasUpstream = repo.hasUpstreamBranch(currentBranch);
            boolean canPush;
            java.util.Set<String> unpushedCommitIds = new java.util.HashSet<>();
            if (hasUpstream) {
                unpushedCommitIds.addAll(repo.remote().getUnpushedCommitIds(currentBranch));
                canPush = !unpushedCommitIds.isEmpty();
            } else {
                canPush = true;
            }
            pushPullState = new GitWorkflow.PushPullState(hasUpstream, hasUpstream, canPush, unpushedCommitIds);
        } catch (GitAPIException e) {
            logger.warn("Failed to evaluate push/pull state", e);
        }

        var cumulativeChanges = new DiffService.CumulativeChanges(
                summarizedChanges.filesChanged(),
                summarizedChanges.totalAdded(),
                summarizedChanges.totalDeleted(),
                summarizedChanges.perFileChanges(),
                commits,
                pushPullState);

        List<UUID> overlappingSessions = findOverlappingSessions(cm, commits);

        return new ReviewScope(cumulativeChanges, overlappingSessions);
    }

    @Blocking
    public static List<UUID> findOverlappingSessions(IContextManager cm, List<CommitInfo> commits) {
        if (commits.isEmpty()) {
            return List.of();
        }

        var sessionManager = cm.getProject().getSessionManager();
        Instant minBound = commits.stream()
                .map(CommitInfo::date)
                .min(Comparator.naturalOrder())
                .orElse(Instant.now());
        Instant maxBound = commits.stream()
                .map(CommitInfo::date)
                .max(Comparator.naturalOrder())
                .orElse(Instant.now());

        Set<String> changeCommitIds = commits.stream().map(CommitInfo::id).collect(Collectors.toSet());

        return sessionManager.listSessions().stream()
                .parallel()
                .filter(s -> s.lastModified().isAfter(minBound) && s.createdAt().isBefore(maxBound))
                .map(SessionManager.SessionInfo::id)
                .filter(id -> {
                    var history = sessionManager.loadHistory(id, cm);
                    return history != null
                            && history.getGitStates().values().stream()
                                    .map(ContextHistory.GitState::commitHash)
                                    .anyMatch(changeCommitIds::contains);
                })
                .toList();
    }
}
