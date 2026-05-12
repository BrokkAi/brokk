package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link CandidateFileProvider} that uses a fast substring scan to find potential usage files.
 *
 * <p>This is useful as a fallback when the import graph is incomplete or unavailable.
 */
public final class TextSearchCandidateProvider implements CandidateFileProvider {

    private static final Logger logger = LogManager.getLogger(TextSearchCandidateProvider.class);

    @Override
    public Set<ProjectFile> findCandidates(CodeUnit target, IAnalyzer analyzer) throws InterruptedException {
        String identifier = target.identifier();
        if (identifier.isBlank()) {
            return Set.of();
        }

        Language language = Languages.fromExtension(target.source().extension());
        Set<ProjectFile> filesToSearch = analyzer.getProject().getAnalyzableFiles(language);
        if (filesToSearch.isEmpty()) {
            return Set.of();
        }

        var matches = new ConcurrentHashMap<ProjectFile, Boolean>();
        var tasks = filesToSearch.stream()
                .<Callable<Void>>map(file -> () -> {
                    try {
                        if (!file.isText()) return null;
                        var contentOpt = file.read();
                        if (contentOpt.isEmpty()) return null;
                        if (contentOpt.get().contains(identifier)) {
                            matches.put(file, true);
                        }
                    } catch (Exception e) {
                        logger.warn("TextSearchCandidateProvider failed for {}: {}", file, e.toString());
                    }
                    return null;
                })
                .toList();
        var futures = UsageAnalysisExecutors.ioExecutor().invokeAll(tasks);
        for (var future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Text search candidate scan failed", e.getCause());
            }
        }
        return Set.copyOf(matches.keySet());
    }
}
