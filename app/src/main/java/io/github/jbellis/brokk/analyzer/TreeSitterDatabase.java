package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.treesitter.TSTree;

/** A collection of all the serializable data associated with a {@link TreeSitterAnalyzer}. */
public final class TreeSitterDatabase {

    // Data structures tracking parsed files
    private final Map<ProjectFile, List<CodeUnit>> topLevelDeclarations = new ConcurrentHashMap<>();
    private final Map<CodeUnit, List<CodeUnit>> childrenByParent = new ConcurrentHashMap<>();
    private final Map<CodeUnit, List<String>> signatures = new ConcurrentHashMap<>();
    private final Map<CodeUnit, List<TreeSitterAnalyzer.Range>> sourceRanges = new ConcurrentHashMap<>();
    // SHA-1 hash of each analysed file, used to detect modifications
    private final Map<ProjectFile, String> fileHashes = new ConcurrentHashMap<>();
    private final Map<ProjectFile, TSTree> parsedTreeCache =
            new ConcurrentHashMap<>(); // Cache parsed trees to avoid redundant parsing

    public record Database(
            Map<ProjectFile, List<CodeUnit>> topLevelDeclarations,
            Map<CodeUnit, List<CodeUnit>> childrenByParent,
            Map<CodeUnit, List<String>> signatures,
            Map<CodeUnit, List<TreeSitterAnalyzer.Range>> sourceRanges,
            Map<ProjectFile, String> fileHashes,
            Map<ProjectFile, TSTree> parsedTreeCache) {}

    // Ensures reads see a consistent view while updates mutate internal maps atomically
    private final ReentrantReadWriteLock globalRwLock = new ReentrantReadWriteLock();

    public TreeSitterDatabase(IProject project, Language language) {
        // todo: Implement
    }

    /** Execute {@code supplier} under the read lock and return its result. */
    private <T> T withReadLock(Supplier<T> supplier) {
        var rl = globalRwLock.readLock();
        rl.lock();
        try {
            return supplier.get();
        } finally {
            rl.unlock();
        }
    }

    /** Execute {@code runnable} under the read lock. */
    private void withReadLock(Runnable runnable) {
        var rl = globalRwLock.readLock();
        rl.lock();
        try {
            runnable.run();
        } finally {
            rl.unlock();
        }
    }

    /**
     * A thread-safe way to interact with the "signatures" field.
     *
     * @param function the callback.
     */
    public <R> R withSignatures(Function<Map<CodeUnit, List<String>>, R> function) {
        return withReadLock(() -> function.apply(signatures));
    }

    /**
     * A thread-safe way to interact with the "childrenByParent" field.
     *
     * @param function the callback.
     */
    public <R> R withChildrenByParent(Function<Map<CodeUnit, List<CodeUnit>>, R> function) {
        return withReadLock(() -> function.apply(childrenByParent));
    }

    /**
     * A thread-safe way to interact with the "sourceRanges" field.
     *
     * @param function the callback.
     */
    public <R> R withSourceRanges(Function<Map<CodeUnit, List<TreeSitterAnalyzer.Range>>, R> function) {
        return withReadLock(() -> function.apply(sourceRanges));
    }

    /**
     * A thread-safe way to interact with the "parsedTreeCache" field.
     *
     * @param function the callback.
     */
    public <R> R withParsedTreeCache(Function<Map<ProjectFile, TSTree>, R> function) {
        return withReadLock(() -> function.apply(parsedTreeCache));
    }

    /**
     * A thread-safe way to interact with the "fileHashes" field.
     *
     * @param function the callback.
     */
    public <R> R withFileHashes(Function<Map<ProjectFile, String>, R> function) {
        return withReadLock(() -> function.apply(fileHashes));
    }

    /**
     * A thread-safe way to interact with the "topLevelDeclarations" field.
     *
     * @param function the callback.
     */
    public <R> R withTopLevelDeclarations(Function<Map<ProjectFile, List<CodeUnit>>, R> function) {
        return withReadLock(() -> function.apply(topLevelDeclarations));
    }

    /**
     * A thread-safe way to interact with all the fields of the database.
     *
     * @param function the callback.
     */
    public <R> R withDatabase(Function<Database, R> function) {
        return withReadLock(() -> function.apply(new Database(
                topLevelDeclarations, childrenByParent, signatures, sourceRanges, fileHashes, parsedTreeCache)));
    }

    /** Frees memory from the parsed AST cache. */
    public void clearCaches() {
        withReadLock(parsedTreeCache::clear);
    }

    /** All CodeUnits we know about (top-level + children). */
    private Stream<CodeUnit> allCodeUnits() {
        return withReadLock(() -> {
            // Stream top-level declarations
            Stream<CodeUnit> topLevelStream =
                    topLevelDeclarations.values().stream().flatMap(Collection::stream);

            // Stream parents from childrenByParent (they might not be in topLevelDeclarations if they are nested)
            Stream<CodeUnit> parentStream = childrenByParent.keySet().stream();

            // Stream children from childrenByParent
            Stream<CodeUnit> childrenStream = childrenByParent.values().stream().flatMap(Collection::stream);

            return Stream.of(topLevelStream, parentStream, childrenStream).flatMap(s -> s);
        });
    }

    /** De-duplicate and materialise into a List once. */
    public List<CodeUnit> uniqueCodeUnitList() {
        return allCodeUnits().distinct().toList();
    }

    public int topLevelDeclarationsSize() {
        return withReadLock(topLevelDeclarations::size);
    }

    public int childrenByParentSize() {
        return withReadLock(childrenByParent::size);
    }

    public int signaturesSize() {
        return withReadLock(signatures::size);
    }

    public int cacheSize() {
        return withReadLock(parsedTreeCache::size);
    }

    public boolean isEmpty() {
        return withReadLock(() -> topLevelDeclarations.isEmpty()
                && signatures.isEmpty()
                && childrenByParent.isEmpty()
                && sourceRanges.isEmpty());
    }
}
