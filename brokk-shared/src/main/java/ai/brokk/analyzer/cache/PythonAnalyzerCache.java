package ai.brokk.analyzer.cache;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Map;
import java.util.Set;

/**
 * Python-specific cache extending the base analyzer cache.
 *
 * <p>Persists usage-graph lookup indexes across analyzer snapshots. These indexes are expensive because Python usage
 * graph resolution can otherwise repeatedly scan all declarations while resolving export and member candidates.
 */
public final class PythonAnalyzerCache extends AnalyzerCache {
    private final Cache<ProjectFile, Map<PythonAnalyzer.MemberKey, CodeUnit>> exactMembersByFileCache;
    private final Cache<CodeUnit, String> classKeyByCodeUnitCache;
    private @org.jetbrains.annotations.Nullable Map<String, Set<CodeUnit>> definitionsByIdentifierIndex;

    public PythonAnalyzerCache() {
        super();
        this.exactMembersByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.classKeyByCodeUnitCache = Caffeine.newBuilder().maximumSize(20_000).build();
    }

    public PythonAnalyzerCache(PythonAnalyzerCache previous, Set<ProjectFile> changedFiles) {
        super(previous, changedFiles);
        this.exactMembersByFileCache = Caffeine.newBuilder().maximumSize(10_000).build();
        this.classKeyByCodeUnitCache = Caffeine.newBuilder().maximumSize(20_000).build();

        if (changedFiles.isEmpty()) {
            this.definitionsByIdentifierIndex = previous.definitionsByIdentifierIndex;
        }
        previous.exactMembersByFileCache.asMap().forEach((file, index) -> {
            if (!changedFiles.contains(file)) {
                this.exactMembersByFileCache.put(file, Map.copyOf(index));
            }
        });
        previous.classKeyByCodeUnitCache.asMap().forEach((codeUnit, classKey) -> {
            if (!changedFiles.contains(codeUnit.source())) {
                this.classKeyByCodeUnitCache.put(codeUnit, classKey);
            }
        });
    }

    public @org.jetbrains.annotations.Nullable Map<String, Set<CodeUnit>> definitionsByIdentifierIndex() {
        return definitionsByIdentifierIndex;
    }

    public void definitionsByIdentifierIndex(Map<String, Set<CodeUnit>> definitionsByIdentifierIndex) {
        this.definitionsByIdentifierIndex = definitionsByIdentifierIndex;
    }

    public Cache<ProjectFile, Map<PythonAnalyzer.MemberKey, CodeUnit>> exactMembersByFileCache() {
        return exactMembersByFileCache;
    }

    public Cache<CodeUnit, String> classKeyByCodeUnitCache() {
        return classKeyByCodeUnitCache;
    }
}
