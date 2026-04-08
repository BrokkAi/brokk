package ai.brokk.git;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.concurrent.LoggingFuture;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.AndRevFilter;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Analyzes repository hotspots by correlating Git churn/authorship with code complexity.
 */
@NullMarked
public class GitHotspotAnalyzer {
    private static final Logger logger = LogManager.getLogger(GitHotspotAnalyzer.class);

    /** Maximum rows returned when {@code maxFiles > 0} is passed to {@link #analyze}. */
    public static final int MAX_FILES_IN_REPORT_HARD_CAP = 500;

    public enum HotspotCategory {
        HOTSPOT, // High activity, high complexity
        ABANDONWARE, // Low activity, high complexity (Dark Zone)
        STABLE, // Low activity, low complexity
        ACTIVE // High activity, low complexity
    }

    public record AuthorInfo(String name, String email, int commits) {}

    public record FileHotspotInfo(
            String path,
            int churn,
            int uniqueAuthors,
            List<AuthorInfo> topAuthors,
            int complexity,
            HotspotCategory category,
            String lastModified) {}

    public record HotspotReport(
            String repository,
            int analyzedCommits,
            String timeframe,
            int totalUniqueFiles,
            boolean truncated,
            List<FileHotspotInfo> files) {}

    private final GitRepo repo;
    private final IAnalyzer analyzer;

    public GitHotspotAnalyzer(GitRepo repo, IAnalyzer analyzer) {
        this.repo = repo;
        this.analyzer = analyzer;
    }

    /**
     * Analyzes hotspots in the repository (no limit on the number of files in the report).
     *
     * @param since inclusive start time for the analysis
     * @param maxCommits maximum number of commits to traverse
     */
    public HotspotReport analyze(Instant since, int maxCommits) throws GitAPIException, IOException {
        return analyze(since, null, maxCommits, 0);
    }

    /**
     * Analyzes hotspots with an optional end time and optional cap on returned files (after sorting by churn).
     *
     * @param since inclusive start of commit time window
     * @param until exclusive end of commit time window, or null for no upper bound
     * @param maxCommits maximum commits to traverse from HEAD
     * @param maxFiles if positive, return at most this many files (capped at {@link #MAX_FILES_IN_REPORT_HARD_CAP});
     *     if zero or negative, return all files (no truncation)
     */
    public HotspotReport analyze(Instant since, @Nullable Instant until, int maxCommits, int maxFiles)
            throws GitAPIException, IOException {
        Repository jgitRepo = repo.getRepository();
        Map<ProjectFile, FileStats> statsMap = new HashMap<>();
        int commitCount = 0;
        String timeframe = formatTimeframe(since, until);

        try (RevWalk walk = new RevWalk(jgitRepo)) {
            ObjectId head = jgitRepo.resolve(Constants.HEAD);
            if (head == null) {
                return new HotspotReport(repo.getWorkTreeRoot().toString(), 0, timeframe, 0, false, List.of());
            }
            walk.markStart(walk.parseCommit(head));
            RevFilter timeFilter = CommitTimeRevFilter.after(since);
            if (until != null) {
                timeFilter = AndRevFilter.create(timeFilter, CommitTimeRevFilter.before(until));
            }
            walk.setRevFilter(timeFilter);

            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(jgitRepo);
                df.setDetectRenames(true);

                RevCommit commit;
                while ((commit = walk.next()) != null && commitCount < maxCommits) {
                    commitCount++;
                    processCommit(commit, df, statsMap);
                }
            }
        }

        List<FileHotspotInfo> hotspotInfos;
        try (LoggingExecutorService executor = ExecutorsUtil.newVirtualThreadExecutor("HotspotAnalyzer", 20)) {
            List<CompletableFuture<FileHotspotInfo>> futures = statsMap.entrySet().stream()
                    .map(entry ->
                            LoggingFuture.supplyAsync(() -> createInfo(entry.getKey(), entry.getValue()), executor))
                    .toList();

            LoggingFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            hotspotInfos = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(FileHotspotInfo::churn).reversed())
                    .collect(Collectors.toList());
        }

        int totalUnique = hotspotInfos.size();
        boolean truncated = false;
        List<FileHotspotInfo> filesOut = hotspotInfos;
        if (maxFiles > 0) {
            int cap = Math.min(maxFiles, MAX_FILES_IN_REPORT_HARD_CAP);
            if (hotspotInfos.size() > cap) {
                truncated = true;
                filesOut = List.copyOf(hotspotInfos.subList(0, cap));
            }
        }

        return new HotspotReport(
                repo.getWorkTreeRoot().toString(), commitCount, timeframe, totalUnique, truncated, filesOut);
    }

    private static String formatTimeframe(Instant since, @Nullable Instant until) {
        if (until == null) {
            return "since " + since;
        }
        return "since " + since + " until " + until;
    }

    void processCommit(RevCommit commit, DiffFormatter df, Map<ProjectFile, FileStats> statsMap) throws IOException {
        var ident = commit.getAuthorIdent();
        String name = ident.getName();
        String email = ident.getEmailAddress();
        Instant commitTime = ident.getWhenAsInstant();

        RevCommit parent = commit.getParentCount() > 0 ? commit.getParent(0) : null;
        List<DiffEntry> diffs = GitRepoData.scanWithFallback(
                df, parent != null ? parent.getTree() : null, commit.getTree(), "GitHotspotAnalyzer");

        for (DiffEntry diff : diffs) {
            String path = diff.getNewPath();
            if (path.equals(DiffEntry.DEV_NULL)) {
                path = diff.getOldPath();
            }

            repo.toProjectFile(path).ifPresent(pf -> {
                FileStats stats = statsMap.computeIfAbsent(pf, k -> new FileStats());
                stats.churn++;
                stats.authorCounts.merge(email, 1, Integer::sum);
                stats.authorNames.putIfAbsent(email, name);
                if (stats.lastModified == null || commitTime.isAfter(stats.lastModified)) {
                    stats.lastModified = commitTime;
                }
            });
        }
    }

    private @Nullable FileHotspotInfo createInfo(ProjectFile pf, FileStats stats) {
        if (!pf.exists()) return null;

        // Find complexity. We look at top-level declarations (usually classes)
        // and take the max complexity of any function found.
        int maxComplexity;
        try {
            maxComplexity = analyzer.getTopLevelDeclarations(pf).stream()
                    .flatMap(cu -> flatten(cu).stream())
                    .filter(CodeUnit::isFunction)
                    .mapToInt(analyzer::computeCyclomaticComplexity)
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            logger.debug("Failed to compute complexity for file {}, defaulting to 0: {}", pf, e.getMessage());
            maxComplexity = 0;
        }

        HotspotCategory category = determineCategory(stats.churn, maxComplexity);

        List<AuthorInfo> topAuthors = stats.authorCounts.entrySet().stream()
                .map(e -> new AuthorInfo(requireNonNull(stats.authorNames.get(e.getKey())), e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(AuthorInfo::commits).reversed())
                .limit(5)
                .toList();

        return new FileHotspotInfo(
                pf.toString(),
                stats.churn,
                stats.authorCounts.size(),
                topAuthors,
                maxComplexity,
                category,
                stats.lastModified != null ? stats.lastModified.toString() : Instant.EPOCH.toString());
    }

    private List<CodeUnit> flatten(CodeUnit cu) {
        List<CodeUnit> result = new ArrayList<>();
        result.add(cu);
        for (CodeUnit child : analyzer.getDirectChildren(cu)) {
            result.addAll(flatten(child));
        }
        return result;
    }

    private HotspotCategory determineCategory(int churn, int complexity) {
        boolean highChurn = churn > 10;
        boolean highComplexity = complexity > 15;

        if (highChurn && highComplexity) return HotspotCategory.HOTSPOT;
        if (!highChurn && highComplexity) return HotspotCategory.ABANDONWARE;
        if (highChurn && !highComplexity) return HotspotCategory.ACTIVE;
        return HotspotCategory.STABLE;
    }

    static class FileStats {
        int churn = 0;
        Map<String, Integer> authorCounts = new HashMap<>();
        Map<String, String> authorNames = new HashMap<>();

        @Nullable
        Instant lastModified = null;
    }
}
