package ai.brokk.git;

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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Analyzes repository hotspots by correlating Git churn/authorship with code complexity.
 */
@NullMarked
public class GitHotspotAnalyzer {

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
            Instant lastModified) {}

    public record HotspotReport(
            String repository, int analyzedCommits, String timeframe, List<FileHotspotInfo> files) {}

    private final GitRepo repo;
    private final IAnalyzer analyzer;

    public GitHotspotAnalyzer(GitRepo repo, IAnalyzer analyzer) {
        this.repo = repo;
        this.analyzer = analyzer;
    }

    /**
     * Analyzes hotspots in the repository.
     *
     * @param since inclusive start time for the analysis
     * @param maxCommits maximum number of commits to traverse
     */
    public HotspotReport analyze(Instant since, int maxCommits) throws GitAPIException, IOException {
        Repository jgitRepo = repo.getRepository();
        Map<ProjectFile, FileStats> statsMap = new HashMap<>();
        int commitCount = 0;

        try (RevWalk walk = new RevWalk(jgitRepo)) {
            ObjectId head = jgitRepo.resolve(Constants.HEAD);
            if (head == null) {
                return new HotspotReport(repo.getWorkTreeRoot().toString(), 0, since.toString(), List.of());
            }
            walk.markStart(walk.parseCommit(head));
            walk.setRevFilter(CommitTimeRevFilter.after(since));

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

        return new HotspotReport(repo.getWorkTreeRoot().toString(), commitCount, since.toString(), hotspotInfos);
    }

    void processCommit(RevCommit commit, DiffFormatter df, Map<ProjectFile, FileStats> statsMap) throws IOException {
        var ident = commit.getAuthorIdent();
        String name = ident.getName();
        String email = ident.getEmailAddress();
        Instant commitTime = ident.getWhenAsInstant();

        RevCommit parent = commit.getParentCount() > 0 ? commit.getParent(0) : null;
        List<DiffEntry> diffs;
        try {
            diffs = df.scan(parent != null ? parent.getTree() : null, commit.getTree());
        } catch (MissingObjectException e) {
            // Rename detection requires blob contents which may be missing in blobless/partial clones.
            // Disable it and retry the scan to proceed with basic path tracking.
            df.setDetectRenames(false);
            diffs = df.scan(parent != null ? parent.getTree() : null, commit.getTree());
        }

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
        int maxComplexity = analyzer.getTopLevelDeclarations(pf).stream()
                .flatMap(cu -> flatten(cu).stream())
                .filter(CodeUnit::isFunction)
                .mapToInt(analyzer::computeCyclomaticComplexity)
                .max()
                .orElse(0);

        HotspotCategory category = determineCategory(stats.churn, maxComplexity);

        List<AuthorInfo> topAuthors = stats.authorCounts.entrySet().stream()
                .map(e -> new AuthorInfo(stats.authorNames.get(e.getKey()), e.getKey(), e.getValue()))
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
                stats.lastModified != null ? stats.lastModified : Instant.EPOCH);
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
