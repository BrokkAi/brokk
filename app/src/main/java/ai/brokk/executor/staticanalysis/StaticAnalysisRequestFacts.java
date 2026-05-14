package ai.brokk.executor.staticanalysis;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class StaticAnalysisRequestFacts {
    private static final Logger logger = LogManager.getLogger(StaticAnalysisRequestFacts.class);
    private static final long MAX_FILES = 10_000;

    private final IAnalyzer analyzer;
    private final Cache<ProjectFile, List<CodeUnit>> declarationsByFile =
            Caffeine.newBuilder().maximumSize(MAX_FILES).build();
    private final Cache<ProjectFile, Integer> maxCyclomaticComplexityByFile =
            Caffeine.newBuilder().maximumSize(MAX_FILES).build();

    StaticAnalysisRequestFacts(IAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    List<CodeUnit> declarations(ProjectFile file) {
        return declarationsByFile.get(file, this::computeDeclarations);
    }

    int maxCyclomaticComplexity(ProjectFile file) {
        return maxCyclomaticComplexityByFile.get(file, this::computeMaxCyclomaticComplexity);
    }

    private List<CodeUnit> computeDeclarations(ProjectFile file) {
        var result = new ArrayList<CodeUnit>();
        var work = new ArrayDeque<>(analyzer.getTopLevelDeclarations(file));
        while (!work.isEmpty()) {
            var next = work.removeFirst();
            result.add(next);
            work.addAll(analyzer.getDirectChildren(next));
        }
        return List.copyOf(result);
    }

    private int computeMaxCyclomaticComplexity(ProjectFile file) {
        try {
            return declarations(file).stream()
                    .filter(CodeUnit::isFunction)
                    .mapToInt(analyzer::computeCyclomaticComplexity)
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            logger.debug("Unable to compute max cyclomatic complexity for {}", file, e);
            return 0;
        }
    }
}
