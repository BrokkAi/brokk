package ai.brokk.analyzer;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.util.SemVer;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistence helper for TreeSitterAnalyzer.AnalyzerState using Apache Fory and gzip compression.
 *
 * <p>Serializes AnalyzerState into DTOs:
 * <ul>
 *   <li>PMap fields are represented as standard Maps or entry lists</li>
 *   <li>SymbolKeyIndex becomes List&lt;String&gt; (keys)</li>
 *   <li>FileProperties omits the parsed TSTree</li>
 *   <li>ProjectFile is serialized via a DTO that guarantees a relative relPath</li>
 * </ul>
 */
public final class TreeSitterStateIO {
    private static final Logger log = LoggerFactory.getLogger(TreeSitterStateIO.class);

    private static final ThreadLocalFory FORY = new ForyBuilder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true) // Required for DTO graph with shared CodeUnits/ProjectFiles
            .buildThreadLocalFory();

    static {
        // Register DTO classes for efficient serialization
        FORY.register(SnapshotDto.class);
        FORY.register(AnalyzerStateDto.class);
        FORY.register(CacheSnapshotDto.class);
        FORY.register(CodeUnitDto.class);
        FORY.register(ProjectFileDto.class);
        FORY.register(CodeUnitEntryDto.class);
        FORY.register(FileStateEntryDto.class);
        FORY.register(FilePropertiesDto.class);
        FORY.register(CodeUnitPropertiesDto.class);
        FORY.register(ImportInfoDto.class);
        FORY.register(SignatureEntryDto.class);
        FORY.register(RawSupertypesEntryDto.class);
        FORY.register(ImportEntryDto.class);
        FORY.register(SupertypeEntryDto.class);
        FORY.register(CodeUnitType.class);
        FORY.register(IAnalyzer.Range.class);
    }

    /**
     * Snapshot schema version in SemVer form: {@code MAJOR.MINOR.PATCH}.
     *
     * <p>Versioning policy:
     * <ul>
     *   <li><b>MAJOR</b>: non-migratable. A different major version must be rebuilt (do not load).</li>
     *   <li><b>MINOR</b>: migratable without rebuild. Minor is required and may change load/migration behavior.</li>
     *   <li><b>PATCH</b>: no migration/rebuild required (e.g., removing an optional field).</li>
     * </ul>
     *
     * <p>At the moment we do not attempt backward compatibility across majors. Minor/patch differences are
     * treated as best-effort load for now.
     *
     * <p>If we start requiring more advanced SemVer features (e.g., version ranges/constraints), consider using a
     * library such as jsemver or semver4j instead of maintaining our own parsing/comparison.
     */
    public static final String SCHEMA_VERSION = "1.0.0";

    private TreeSitterStateIO() {}

    /* ================= DTOs ================= */

    /**
     * A minimal, serialization-safe representation of ProjectFile that
     * enforces that relPath is stored as a relative string.
     */
    public record ProjectFileDto(String root, String relPath) {}

    /**
     * A serialization-safe representation of CodeUnit using ProjectFileDto.
     */
    public record CodeUnitDto(
            ProjectFileDto source,
            CodeUnitType kind,
            String packageName,
            String shortName,
            @Nullable String signature) {}

    /**
     * DTO for structured import information.
     */
    public record ImportInfoDto(
            String rawSnippet, boolean isWildcard, @Nullable String identifier, @Nullable String alias) {}

    /**
     * DTO for AnalyzerState with only serializable components.
     *
     * NOTE: import/type-hierarchy graphs are no longer part of the persisted AnalyzerState.
     * They are represented transiently in a serializable cache snapshot when desired.
     */
    public record AnalyzerStateDto(
            Map<String, List<CodeUnitDto>> symbolIndex,
            List<CodeUnitEntryDto> codeUnitState,
            List<FileStateEntryDto> fileState,
            List<String> symbolKeys,
            long snapshotEpochNanos) {}

    /**
     * DTO for CodeUnitProperties.
     */
    public record CodeUnitPropertiesDto(List<CodeUnitDto> children, List<IAnalyzer.Range> ranges, boolean hasBody) {}

    /**
     * DTO entry for CodeUnit -> CodeUnitProperties maps.
     */
    public record CodeUnitEntryDto(CodeUnitDto key, CodeUnitPropertiesDto value) {}

    /**
     * DTO entry for ProjectFile -> FileProperties maps.
     */
    public record FileStateEntryDto(ProjectFileDto key, FilePropertiesDto value) {}

    /**
     * DTO for TreeSitterAnalyzer.FileProperties without the TSTree.
     */
    public record FilePropertiesDto(
            List<CodeUnitDto> topLevelCodeUnits, List<ImportInfoDto> importStatements, boolean containsTests) {}

    /**
     * Save an AnalyzerState together with an optional serializable cache snapshot into a versioned top-level
     * snapshot object. This new top-level model cleanly separates the immutable AnalyzerState from the
     * transient AnalyzerCache representation (a serializable view of the currently-populated transient caches).
     *
     * For backward compatibility, callers that only have an AnalyzerState may call the old save(state, file)
     * which delegates to this method with an empty cache snapshot.
     */
    @Blocking
    public static void save(
            TreeSitterAnalyzer.AnalyzerState state,
            ai.brokk.analyzer.cache.AnalyzerCache.CacheSnapshot cacheSnapshot,
            Path file) {
        long startMs = System.currentTimeMillis();
        try {
            var dto = toTopLevelDto(state, cacheSnapshot);
            byte[] foryBytes = FORY.serialize(dto);

            AtomicWrites.save(file, out -> {
                try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
                    gzipOut.write(foryBytes);
                }
            });

            long durMs = System.currentTimeMillis() - startMs;
            log.debug("Saved TreeSitter Snapshot (state + cache view) to {} in {} ms", file, durMs);
        } catch (IOException e) {
            log.warn("Failed to save TreeSitter Snapshot to {}: {}", file, e.getMessage(), e);
        }
    }

    /**
     * Backwards-compatible overload for callers that only provide AnalyzerState (no cache snapshot).
     * This produces a SnapshotDto where cacheSnapshot is null.
     */
    @Blocking
    public static void save(TreeSitterAnalyzer.AnalyzerState state, Path file) {
        long startMs = System.currentTimeMillis();
        try {
            var asd = toDto(state);
            var dto = new SnapshotDto(SCHEMA_VERSION, asd, null);
            byte[] foryBytes = FORY.serialize(dto);

            AtomicWrites.save(file, out -> {
                try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
                    gzipOut.write(foryBytes);
                }
            });

            long durMs = System.currentTimeMillis() - startMs;
            log.debug("Saved TreeSitter Snapshot (state only) to {} in {} ms", file, durMs);
        } catch (IOException e) {
            log.warn("Failed to save TreeSitter Snapshot to {}: {}", file, e.getMessage(), e);
        }
    }

    /**
     * Load an AnalyzerState from the provided file.
     * Returns Optional.empty() if file is missing or deserialization fails.
     *
     * Note: The on-disk format is a versioned top-level SnapshotDto that contains:
     *  - schemaVersion (string)
     *  - analyzerState (AnalyzerStateDto)
     *  - cacheSnapshot (CacheSnapshotDto) // optional/nullable
     *
     * For compatibility we read the top-level SnapshotDto and extract the AnalyzerState portion.
     * Future versions may use schemaVersion to change load behavior or to attempt automatic migrations.
     *
     * Legacy snapshots (pre-v1) stored AnalyzerStateDto directly at the root without the SnapshotDto wrapper.
     * When we detect such a snapshot (null schemaVersion and null analyzerState in the parsed SnapshotDto),
     * we return empty to trigger a rebuild rather than attempting complex migration.
     */
    /**
     * Low-level helper for tests to inspect the raw SnapshotDto without rehydrating full objects.
     */
    /**
     * Test-only helper to write a raw SnapshotDto to disk using the production pipeline.
     */
    @Blocking
    static void saveRawSnapshotForTest(SnapshotDto dto, Path file) throws IOException {
        byte[] foryBytes = FORY.serialize(dto);
        AtomicWrites.save(file, out -> {
            try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
                gzipOut.write(foryBytes);
            }
        });
    }

    @Blocking
    static Optional<SnapshotDto> loadRaw(Path file) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] bytes = in.readAllBytes();
            // NB: This is nullable
            return Optional.ofNullable(FORY.deserialize(bytes, SnapshotDto.class));
        }
    }

    /**
     * Loads an AnalyzerState from the provided file (legacy/simple API). Returns Optional.empty() if file missing or corrupt.
     *
     * Prefer {@link #loadWithCache(Path)} when the caller wants the transient cache snapshot restored.
     */
    @Blocking
    public static Optional<TreeSitterAnalyzer.AnalyzerState> load(Path file) {
        var loaded = loadWithCache(file);
        return loaded.map(swc -> swc.state());
    }

    /**
     * Result container for a loaded snapshot that includes both the immutable AnalyzerState and a
     * reconstructed AnalyzerCache instance populated with forward mappings from the serialized snapshot.
     *
     * The reconstructed AnalyzerCache is suitable for passing to the TreeSitterAnalyzer snapshot-based
     * constructor. Reverse mappings for bidirectional caches are deliberately left to be populated
     * lazily by the analyzer (matching the runtime behavior of AnalyzerCache transfer constructor).
     */
    public record SnapshotWithCache(TreeSitterAnalyzer.AnalyzerState state, AnalyzerCache cache) {}

    /**
     * Load an AnalyzerState and, when present in the snapshot, a serialized view of the AnalyzerCache.
     * Returns Optional.empty() if file is missing or deserialization fails.
     *
     * This method rehydrates an AnalyzerCache instance and populates forward mappings (signatures,
     * rawSupertypes, imports forward, typeHierarchy forward). Reverse mappings are reconstructed
     * from the forward mappings during load to provide a warm start for analyzer operations.
     */
    @Blocking
    public static Optional<SnapshotWithCache> loadWithCache(Path file) {
        if (!Files.exists(file)) {
            log.debug("Analyzer state file does not exist: {}", file);
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();
        try {
            byte[] gunzipped;
            try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                gunzipped = in.readAllBytes();
            }

            SnapshotDto top = FORY.deserialize(gunzipped, SnapshotDto.class);

            if (!isSchemaVersionLoadable(top.schemaVersion())) {
                log.debug(
                        "Snapshot schemaVersion not loadable: expected={}, found={}. Will rebuild.",
                        SCHEMA_VERSION,
                        top.schemaVersion());
                return Optional.empty();
            }

            var state = fromDto(top.analyzerState());
            AnalyzerCache cache = new AnalyzerCache();
            if (top.cacheSnapshot() != null) {
                restoreCacheFromDto(cache, top.cacheSnapshot());
            }

            long durMs = System.currentTimeMillis() - startMs;
            log.debug(
                    "Loaded TreeSitter AnalyzerState (+cache view) from {} (schema={}) in {} ms",
                    file,
                    top.schemaVersion(),
                    durMs);
            return Optional.of(new SnapshotWithCache(state, cache));
        } catch (ZipException | EOFException e) {
            log.debug("Analyzer state at {} is corrupt or truncated; will rebuild ({}).", file, e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.debug("I/O error reading analyzer state at {}; will rebuild ({}).", file, e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Unexpected error loading TreeSitter AnalyzerState from {}; will rebuild.", file, e);
            return Optional.empty();
        }
    }

    /**
     * Populate the provided AnalyzerCache instance using the serialized CacheSnapshotDto.
     *
     * Forward mappings are restored from the DTO, and reverse mappings (for BidirectionalCache)
     * are reconstructed from those forward mappings to ensure the cache is fully warm upon return.
     */
    private static void restoreCacheFromDto(AnalyzerCache target, CacheSnapshotDto dto) {
        // Restore signatures
        for (var entry : dto.signatures()) {
            CodeUnit key = fromDto(entry.key());
            List<String> value = List.copyOf(entry.value());
            target.signatures().put(key, value);
        }

        // Restore raw supertypes
        for (var entry : dto.rawSupertypes()) {
            CodeUnit key = fromDto(entry.key());
            List<String> value = List.copyOf(entry.value());
            target.rawSupertypes().put(key, value);
        }

        // Restore imports forward and populate reverse mappings so consumers can query reverse quickly.
        for (var entry : dto.importsForward()) {
            ProjectFile pf = fromDto(entry.key());
            Set<CodeUnit> units =
                    entry.value().stream().map(TreeSitterStateIO::fromDto).collect(Collectors.toSet());
            target.imports().putForward(pf, units);

            // Populate reverse mapping for each CodeUnit's source ProjectFile.
            for (CodeUnit cu : units) {
                ProjectFile cuSource = cu.source();
                target.imports().updateReverse(cuSource, existing -> {
                    Set<ProjectFile> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
                    set.add(pf);
                    return set;
                });
            }
        }

        // Restore typeHierarchy forward (supertypes) and populate reverse mappings (subtypes)
        for (var entry : dto.typeHierarchyForward()) {
            CodeUnit key = fromDto(entry.key());
            List<CodeUnit> value =
                    entry.value().stream().map(TreeSitterStateIO::fromDto).toList();
            target.typeHierarchy().putForward(key, value);

            // Populate reverse mapping: for each supertype, record 'key' as its subtype
            for (CodeUnit superCu : value) {
                target.typeHierarchy().updateReverse(superCu, existing -> {
                    Set<CodeUnit> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
                    set.add(key);
                    return set;
                });
            }
        }
    }

    /* ================= Converters ================= */

    /**
     * Top-level Snapshot DTO that will be written to disk. Includes a schemaVersion so future migrations can alter
     * behavior based on that version.
     */
    public record SnapshotDto(
            String schemaVersion, AnalyzerStateDto analyzerState, @Nullable CacheSnapshotDto cacheSnapshot) {}

    /**
     * Serializable view of AnalyzerCache.CacheSnapshot. Note: not all cache contents are persisted;
     * some items (e.g., TSTree) are intentionally omitted because they are not easily serializable or safe to persist.
     * The goal is to allow transfer of presentation-heavy but safe data such as signatures and raw supertypes,
     * and forward mappings for imports/typeHierarchy.
     *
     * <p>We store maps as explicit entry lists (pairs) to keep the DTO schema simple and avoid complex map keys,
     * which helps keep the Fory schema stable.
     */
    public record CacheSnapshotDto(
            List<SignatureEntryDto> signatures,
            List<RawSupertypesEntryDto> rawSupertypes,
            List<ImportEntryDto> importsForward,
            List<SupertypeEntryDto> typeHierarchyForward) {}

    /**
     * Entry DTO for signatures: (CodeUnitDto key) -> List<String> value.
     * Used to avoid using complex objects as Map keys.
     */
    public record SignatureEntryDto(CodeUnitDto key, List<String> value) {}

    /**
     * Entry DTO for raw supertypes map: (CodeUnitDto key) -> List<String> value.
     */
    public record RawSupertypesEntryDto(CodeUnitDto key, List<String> value) {}

    /**
     * Entry DTO for imports forward mapping: (ProjectFileDto key) -> List<CodeUnitDto> value.
     * This mirrors how importsForward is serialized as a list of entries rather than a map with complex keys.
     */
    public record ImportEntryDto(ProjectFileDto key, List<CodeUnitDto> value) {}

    /**
     * Entry DTO for type hierarchy forward mapping: (CodeUnitDto key) -> List<CodeUnitDto> value.
     * Used to persist the forward supertypes mapping from the AnalyzerCache view.
     */
    public record SupertypeEntryDto(CodeUnitDto key, List<CodeUnitDto> value) {}

    /**
     * Convert live AnalyzerState to a serializable DTO.
     */
    public static AnalyzerStateDto toDto(TreeSitterAnalyzer.AnalyzerState state) {
        // symbolIndex -> deep copy to DTO
        Map<String, List<CodeUnitDto>> symbolIndexCopy = new HashMap<>();
        for (var e : state.symbolIndex().entrySet()) {
            var list = new ArrayList<CodeUnitDto>(e.getValue().size());
            for (var cu : e.getValue()) list.add(toDto(cu));
            symbolIndexCopy.put(e.getKey(), list);
        }

        // codeUnitState -> entries list
        List<CodeUnitEntryDto> cuEntries = new ArrayList<>(state.codeUnitState().size());
        for (var e : state.codeUnitState().entrySet()) {
            var props = e.getValue();
            var childrenDtos = new ArrayList<>(
                    props.children().stream().map(TreeSitterStateIO::toDto).toList());

            var propsDto = new CodeUnitPropertiesDto(childrenDtos, new ArrayList<>(props.ranges()), props.hasBody());

            cuEntries.add(new CodeUnitEntryDto(toDto(e.getKey()), propsDto));
        }

        // fileState -> entries list (omit parsed tree)
        List<FileStateEntryDto> fileEntries = new ArrayList<>(state.fileState().size());
        for (var e : state.fileState().entrySet()) {
            var fileProps = e.getValue();

            var topLevelDtos =
                    new ArrayList<CodeUnitDto>(fileProps.topLevelCodeUnits().size());
            for (var cu : fileProps.topLevelCodeUnits()) topLevelDtos.add(toDto(cu));

            var importDtos = new ArrayList<>(fileProps.importStatements().stream()
                    .map(TreeSitterStateIO::toDto)
                    .toList());

            var fpDto = new FilePropertiesDto(topLevelDtos, importDtos, fileProps.containsTests());
            fileEntries.add(new FileStateEntryDto(toDto(e.getKey()), fpDto));
        }

        // Symbol keys for the index
        ArrayList<String> symbolKeys = new ArrayList<>();
        for (String key : state.symbolKeyIndex().all()) {
            symbolKeys.add(key);
        }

        return new AnalyzerStateDto(symbolIndexCopy, cuEntries, fileEntries, symbolKeys, state.snapshotEpochNanos());
    }

    /**
     * Convert a cache snapshot into a serializable DTO.
     */
    public static CacheSnapshotDto cacheSnapshotToDto(AnalyzerCache.CacheSnapshot snapshot) {
        // Convert signatures map into a list of SignatureEntryDto to avoid complex map keys.
        List<SignatureEntryDto> signatures = new ArrayList<>();
        snapshot.signatures()
                .forEach((cu, sigs) -> signatures.add(new SignatureEntryDto(toDto(cu), new ArrayList<>(sigs))));

        // Convert raw supertypes map into a list of RawSupertypesEntryDto.
        List<RawSupertypesEntryDto> rawSupertypes = new ArrayList<>();
        snapshot.rawSupertypes()
                .forEach((cu, supers) ->
                        rawSupertypes.add(new RawSupertypesEntryDto(toDto(cu), new ArrayList<>(supers))));

        List<ImportEntryDto> importsForward = new ArrayList<>();
        snapshot.imports()
                .forEachForward((file, units) -> importsForward.add(new ImportEntryDto(
                        toDto(file),
                        new ArrayList<>(
                                units.stream().map(TreeSitterStateIO::toDto).toList()))));

        List<SupertypeEntryDto> typeForward = new ArrayList<>();
        snapshot.typeHierarchy()
                .forEachForward((cu, supers) -> typeForward.add(new SupertypeEntryDto(
                        toDto(cu),
                        new ArrayList<>(
                                supers.stream().map(TreeSitterStateIO::toDto).toList()))));

        return new CacheSnapshotDto(signatures, rawSupertypes, importsForward, typeForward);
    }

    /**
     * Produce the top-level SnapshotDto combining the AnalyzerState DTO and an optional
     * serializable cache snapshot. Centralizes the schemaVersion assignment so future
     * migrations can gate behavior based on this single constant.
     *
     * If cacheSnapshot is provided, it is always included in the DTO even if empty.
     */
    public static SnapshotDto toTopLevelDto(
            TreeSitterAnalyzer.AnalyzerState state, AnalyzerCache.CacheSnapshot cacheSnapshot) {
        CacheSnapshotDto csd = cacheSnapshotToDto(cacheSnapshot);
        AnalyzerStateDto asd = toDto(state);
        return new SnapshotDto(SCHEMA_VERSION, asd, csd);
    }

    /**
     * Convert DTO back into an immutable AnalyzerState snapshot.
     */
    public static TreeSitterAnalyzer.AnalyzerState fromDto(AnalyzerStateDto dto) {
        // Rebuild symbol index PMap
        Map<String, Set<CodeUnit>> symbolIndexMap = new HashMap<>();
        for (var e : dto.symbolIndex().entrySet()) {
            var set = new HashSet<CodeUnit>(e.getValue().size());
            for (var cuDto : e.getValue()) set.add(fromDto(cuDto));
            symbolIndexMap.put(e.getKey(), set);
        }
        PMap<String, Set<CodeUnit>> symbolIndex = HashTreePMap.from(symbolIndexMap);

        // Rebuild codeUnitState PMap
        Map<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties> cuState = new HashMap<>();
        for (var entry : dto.codeUnitState()) {
            var v = entry.value();

            var props = new TreeSitterAnalyzer.CodeUnitProperties(
                    v.children().stream().map(TreeSitterStateIO::fromDto).toList(), v.ranges(), v.hasBody());

            cuState.put(fromDto(entry.key()), props);
        }
        PMap<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties> codeUnitState = HashTreePMap.from(cuState);

        // Rebuild fileState PMap
        Map<ProjectFile, TreeSitterAnalyzer.FileProperties> fileStateMap = new HashMap<>();
        for (var entry : dto.fileState()) {
            var v = entry.value();

            var topLevel = new ArrayList<CodeUnit>(v.topLevelCodeUnits().size());
            for (var cuDto : v.topLevelCodeUnits()) topLevel.add(fromDto(cuDto));

            var imports = v.importStatements().stream()
                    .map(TreeSitterStateIO::fromDto)
                    .toList();

            var fp = new TreeSitterAnalyzer.FileProperties(topLevel, imports, v.containsTests());
            fileStateMap.put(fromDto(entry.key()), fp);
        }
        PMap<ProjectFile, TreeSitterAnalyzer.FileProperties> fileState = HashTreePMap.from(fileStateMap);

        // Rebuild SymbolKeyIndex
        var keySet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        keySet.addAll(dto.symbolKeys());
        var unmodifiableKeys = Collections.unmodifiableNavigableSet(keySet);
        var symbolKeyIndex = new TreeSitterAnalyzer.SymbolKeyIndex(unmodifiableKeys);

        // Construct new immutable AnalyzerState (no import/type graphs)
        return new TreeSitterAnalyzer.AnalyzerState(
                symbolIndex, codeUnitState, fileState, symbolKeyIndex, dto.snapshotEpochNanos());
    }

    /* ================= Helpers ================= */

    private static boolean isSchemaVersionLoadable(@Nullable String foundStr) {
        SemVer expected = SemVer.parse(SCHEMA_VERSION);
        SemVer found = SemVer.parse(foundStr);

        if (expected == null || found == null) {
            log.debug("Invalid schema version: expected={}, found={}. Will rebuild.", SCHEMA_VERSION, foundStr);
            return false;
        }

        if (expected.major() != found.major()) {
            log.debug("Major version mismatch: expected={}, found={}. Will rebuild.", expected, found);
            return false;
        }

        if (expected.minor() != found.minor() || expected.patch() != found.patch()) {
            log.info("Loading snapshot with version difference: expected={}, found={}", expected, found);
        }

        return true;
    }

    /**
     * Parse a root string that may be a plain path or a file: URI into an absolute, normalized Path.
     * Handles older snapshots that persisted root as "file:/...".
     */
    private static Path parseRootPath(String text) {
        try {
            if (text.startsWith("file:")) {
                URI uri = new URI(text);
                return Path.of(uri).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // fall through to plain path parsing
        }
        Path p = Path.of(text);
        if (!p.isAbsolute()) {
            // If it still has a file: prefix but wasn't parsed as URI above, strip and try again
            if (text.startsWith("file:")) {
                String stripped = text.substring("file:".length());
                p = Path.of(stripped);
            }
            p = p.toAbsolutePath();
        }
        return p.normalize();
    }

    private static CodeUnitDto toDto(CodeUnit cu) {
        return new CodeUnitDto(toDto(cu.source()), cu.kind(), cu.packageName(), cu.shortName(), cu.signature());
    }

    private static CodeUnit fromDto(CodeUnitDto dto) {
        return new CodeUnit(fromDto(dto.source()), dto.kind(), dto.packageName(), dto.shortName(), dto.signature());
    }

    private static ProjectFileDto toDto(ProjectFile pf) {
        Path root = pf.getRoot().toAbsolutePath().normalize();
        Path rel = pf.getRelPath();

        String relStr;
        if (rel.isAbsolute()) {
            // attempt to normalize into a path relative to root
            Path nr = root.toAbsolutePath().normalize();
            Path rl = rel.toAbsolutePath().normalize();
            if (rl.startsWith(nr)) {
                relStr = nr.relativize(rl).toString();
            } else {
                // best-effort fallback; use just the file name to keep it relative
                relStr = rl.getFileName() != null ? rl.getFileName().toString() : rl.toString();
                log.debug(
                        "ProjectFile relPath was absolute and outside root; falling back to fileName only: root={}, abs={}",
                        nr,
                        rl);
            }
        } else {
            relStr = rel.toString();
        }
        return new ProjectFileDto(root.toString(), relStr);
    }

    private static ImportInfoDto toDto(ImportInfo info) {
        return new ImportInfoDto(info.rawSnippet(), info.isWildcard(), info.identifier(), info.alias());
    }

    private static ImportInfo fromDto(ImportInfoDto dto) {
        return new ImportInfo(dto.rawSnippet(), dto.isWildcard(), dto.identifier(), dto.alias());
    }

    private static ProjectFile fromDto(ProjectFileDto dto) {
        Path root = parseRootPath(dto.root());
        Path rel = Path.of(dto.relPath());

        if (rel.isAbsolute()) {
            Path nr = root.toAbsolutePath().normalize();
            Path rl = rel.toAbsolutePath().normalize();
            if (rl.startsWith(nr)) {
                rel = nr.relativize(rl);
            } else {
                // best-effort fallback; use just the file name to keep it relative
                log.debug(
                        "Loaded ProjectFileDto.relPath was absolute and outside root; using fileName only: root={}, abs={}",
                        nr,
                        rl);
                Path fileName = rl.getFileName();
                rel = (fileName != null) ? fileName : Path.of("");
            }
        }

        return new ProjectFile(root, rel);
    }
}
