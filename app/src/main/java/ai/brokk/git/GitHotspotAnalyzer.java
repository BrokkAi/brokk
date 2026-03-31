package ai.brokk.git;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
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

    public record FileHotspotInfo(
            String path,
            int churn,
            int uniqueAuthors,
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

        List<FileHotspotInfo> hotspotInfos = statsMap.entrySet().stream()
                .map(entry -> createInfo(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(FileHotspotInfo::churn).reversed())
                .collect(Collectors.toList());

        return new HotspotReport(repo.getWorkTreeRoot().toString(), commitCount, since.toString(), hotspotInfos);
    }

    private void processCommit(RevCommit commit, DiffFormatter df, Map<ProjectFile, FileStats> statsMap)
            throws IOException {
        String author = commit.getAuthorIdent().getEmailAddress();
        Instant commitTime = commit.getAuthorIdent().getWhenAsInstant();

        RevCommit parent = commit.getParentCount() > 0 ? commit.getParent(0) : null;
        List<DiffEntry> diffs = df.scan(parent != null ? parent.getTree() : null, commit.getTree());

        for (DiffEntry diff : diffs) {
            String path = diff.getNewPath();
            if (path.equals(DiffEntry.DEV_NULL)) {
                path = diff.getOldPath();
            }

            repo.toProjectFile(path).ifPresent(pf -> {
                FileStats stats = statsMap.computeIfAbsent(pf, k -> new FileStats());
                stats.churn++;
                stats.authors.add(author);
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

        return new FileHotspotInfo(
                pf.toString(),
                stats.churn,
                stats.authors.size(),
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

    private static class FileStats {
        int churn = 0;
        Set<String> authors = new HashSet<>();

        @Nullable
        Instant lastModified = null;
    }
}
