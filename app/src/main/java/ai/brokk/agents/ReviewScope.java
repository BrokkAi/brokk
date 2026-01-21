package ai.brokk.agents;

import ai.brokk.IContextManager;
import ai.brokk.SessionManager;
import ai.brokk.context.Context;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.DiffService;
import ai.brokk.context.SpecialTextType;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoData.FileDiff;
import ai.brokk.git.GitWorkflow;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import com.google.common.collect.Sets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;

public record ReviewScope(DiffService.CumulativeChanges changes, ReviewScope.Metadata metadata) {
    private static final Logger logger = LogManager.getLogger(ReviewScope.class);

    // sessionIds is left as UUID keys instead of materializing to ContextHistory, because we need to serialize
    // through json for Activity History
    public record Metadata(String fromRef, String toRef, List<UUID> sessionIds) {
        public String toJson() {
            try {
                return Json.getMapper().writeValueAsString(this);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize ReviewScope.Metadata", e);
            }
        }

        public static Metadata fromJson(String json) throws ReviewLoadContextException {
            try {
                return Json.getMapper().readValue(json, Metadata.class);
            } catch (JsonProcessingException e) {
                throw new ReviewLoadContextException("Failed to parse ReviewScope.Metadata", e);
            }
        }
    }

    /**
     * returns a ReviewContext for changes from ($fromRef..HEAD]
     */
    @Blocking
    public static ReviewScope fromBaseline(IContextManager cm, String fromRef) {
        return fromBaseline(cm, fromRef, "HEAD");
    }

    /**
     * For use loading external reviews. We won't be able to use analyzer correctly unless endRef==HEAD,
     * so you probably shouldn't use this otherwise.
     */
    @Blocking
    public static ReviewScope fromBaseline(IContextManager cm, String fromRef, String endRef) {
        var repo = (GitRepo) cm.getProject().getRepo();

        String resolvedFrom;
        String resolvedEnd;
        try {
            resolvedFrom = repo.resolveToCommit(fromRef).name();
            resolvedEnd = repo.resolveToCommit(endRef.equals("WORKING") ? "HEAD" : endRef)
                    .name();
        } catch (GitAPIException e) {
            throw new RuntimeException("Failed to resolve references: " + fromRef + ", " + endRef, e);
        }

        List<CommitInfo> commits = List.of();
        if (!resolvedEnd.equals(resolvedFrom)) {
            try {
                commits = repo.listCommitsBetweenBranches(resolvedFrom, resolvedEnd, false);
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        }
        // use endRef instead of resolvedEnd to pass WORKING along
        var summarizedChanges = DiffService.computeCumulativeDiff(repo, resolvedFrom, endRef, commits);

        GitWorkflow.PushPullState pushPullState = null;
        try {
            String currentBranch = repo.getCurrentBranch();
            boolean hasUpstream = repo.hasUpstreamBranch(currentBranch);
            boolean canPush;
            Set<String> unpushedCommitIds = new java.util.HashSet<>();
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

        return new ReviewScope(cumulativeChanges, new Metadata(resolvedFrom, resolvedEnd, overlappingSessions));
    }

    @Blocking
    public static ReviewScope fromContext(IContextManager cm, Context context) throws ReviewLoadException {
        var metadataFragment = context.getSpecial(SpecialTextType.REVIEW_METADATA.description());
        var diffFragment = context.getSpecial(SpecialTextType.REVIEW_DIFF.description());

        if (metadataFragment.isEmpty() || diffFragment.isEmpty()) {
            throw new ReviewLoadContextException("Missing diff and/or metadata fragments");
        }

        Metadata metadata = Metadata.fromJson(metadataFragment.get().text().join());
        String diffText = diffFragment.get().text().join();

        Optional<UnifiedDiff> parsedDiff = ContentDiffUtils.parseUnifiedDiff(diffText);
        if (parsedDiff.isEmpty()) {
            throw new ReviewLoadContextException("Failed to parse review diff from context");
        }

        var repo = (GitRepo) cm.getProject().getRepo();
        List<FileDiff> fileDiffs = new ArrayList<>();

        var files = parsedDiff.get().getFiles();

        for (UnifiedDiffFile file : files) {
            String oldPathStr = file.getFromFile();
            String newPathStr = file.getToFile();

            var oldFile = oldPathStr == null || oldPathStr.equals("/dev/null") ? null : cm.toFile(oldPathStr);
            var newFile = newPathStr == null || newPathStr.equals("/dev/null") ? null : cm.toFile(newPathStr);

            String oldText = "";
            String newText = "";

            try {
                if (oldFile != null) {
                    oldText = repo.data().getRefContent(metadata.fromRef(), oldFile);
                }
                if (newFile != null) {
                    newText = repo.data().getRefContent(metadata.toRef(), newFile);
                }
            } catch (GitAPIException e) {
                logger.warn(
                        "Failed to load content for file in ReviewScope.fromContext: {}",
                        newPathStr != null ? newPathStr : oldPathStr,
                        e);
                throw new ReviewLoadGitException(
                        "Failed to load content for " + (newPathStr != null ? newPathStr : oldPathStr), e);
            }

            fileDiffs.add(new FileDiff(oldFile, newFile, oldText, newText));
        }

        var cumulativeChanges = new DiffService.CumulativeChanges(
                fileDiffs.size(),
                0, // totalAdded/Deleted are not easily reconstructible without re-parsing chunks
                0,
                fileDiffs,
                List.of(),
                null);

        return new ReviewScope(cumulativeChanges, metadata);
    }

    public static class ReviewLoadException extends Exception {
        public ReviewLoadException(String message) {
            super(message);
        }

        public ReviewLoadException(String message, Throwable th) {
            super(message, th);
        }
    }

    public static class ReviewLoadContextException extends ReviewLoadException {
        public ReviewLoadContextException(String message) {
            super(message);
        }

        public ReviewLoadContextException(String message, Throwable th) {
            super(message, th);
        }
    }

    public static class ReviewLoadGitException extends ReviewLoadException {
        public ReviewLoadGitException(String message, GitAPIException e) {
            super(message, e);
        }
    }

    @Blocking
    public static List<UUID> findOverlappingSessions(IContextManager cm, List<CommitInfo> commits) {
        if (commits.isEmpty()) {
            return List.of();
        }

        Instant minBound = commits.stream()
                .map(CommitInfo::date)
                .min(Comparator.naturalOrder())
                .orElse(Instant.now());
        Instant maxBound = commits.stream()
                .map(CommitInfo::date)
                .max(Comparator.naturalOrder())
                .orElse(Instant.now());

        Set<String> changeCommitIds = commits.stream().map(CommitInfo::id).collect(Collectors.toSet());
        SessionManager sessionManager;
        try {
            sessionManager = cm.getProject().getSessionManager();
        } catch (UnsupportedOperationException e) {
            // fucking tight coupling
            return List.of();
        }
        var localSessions = sessionManager.filterSessions(minBound, maxBound).stream()
                .map(SessionManager.SessionInfo::id)
                .collect(Collectors.toSet());
        var foreignSessions = sessionManager.filterForeignSessions(minBound, maxBound).stream()
                .map(SessionManager.MinimalSessionInfo::id)
                .collect(Collectors.toSet());

        return Sets.union(localSessions, foreignSessions).stream()
                .filter(id -> {
                    if (sessionManager.countAiResponses(id) <= 0) {
                        return false;
                    }
                    var history = sessionManager.loadHistory(id, cm);
                    return history != null
                            && history.getGitStates().values().stream()
                                    .map(ContextHistory.GitState::commitHash)
                                    .anyMatch(changeCommitIds::contains);
                })
                .collect(Collectors.toList());
    }
}
