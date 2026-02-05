package ai.brokk.analyzer;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistence helper for TreeSitterAnalyzer.AnalyzerState using an explicit, versioned on-disk format.
 *
 * <p>High-level on-disk format
 * - The entire payload is GZIP-compressed to reduce size and avoid accidental text-editing by users.
 * - The compressed payload begins with a length-prefixed UTF-8 schema version string. This string is the primary
 *   compatibility token and MUST be read and validated before attempting to parse the rest of the payload.
 * - After the version string the file contains a sequence of length-prefixed binary sections that represent the
 *   AnalyzerStateDto graph. At a high level these sections include:
 *     - symbolIndex (Map<String, List<CodeUnitDto>>)
 *     - codeUnitState (List of CodeUnitEntryDto)
 *     - fileState (List of FileStateEntryDto)
 *     - imports and reverseImports (forward/reverse import graphs)
 *     - optional type hierarchy sections (supertypes and subtypes)
 *     - symbolKeys (list of strings used for prefix/autocomplete)
 *     - snapshotEpochNanos (long)
 *
 * <p>Strings are encoded as an int length (byte count) followed by UTF-8 bytes. Primitive lengths and counts are
 * written as fixed-size integers (big-endian). The concrete writer/reader methods are intentionally explicit so the
 * on-disk layout is predictable and easy to evolve.
 *
 * <p>Versioning and migration strategy
 * - The schema version is an explicit UTF-8 string written at the very start of the (uncompressed) stream. The
 *   version token is authoritative: when loading, the reader first attempts to read this string. If the token does
 *   not match the current schema version the loader treats the snapshot as incompatible and returns Optional.empty().
 * - This design favors safety and forward progress: incompatible or unknown versions do not cause runtime exceptions
 *   or partial/inconsistent state to be loaded into the analyzer. Instead the analyzer rebuilds from source when a
 *   snapshot cannot be trusted.
 * - When the schema needs to evolve, bump the CURRENT_SCHEMA_VERSION constant to a new semantic token (for example
 *   "1.1" or "2.0"), and add migration logic in the loader if you want to accept older versions explicitly.
 *   Prefer explicit migration code paths over implicit tolerant parsing: explicit migrations are easier to review and
 *   reason about, and they leave a clear audit trail for schema changes.
 *
 * <p>Error handling, corruption, and truncated payloads
 * - The loader treats any I/O error, malformed header, truncated stream, or unexpected end-of-file as an indication
 *   that the persisted snapshot cannot be trusted. In all of these cases load(...) returns Optional.empty() and logs
 *   a debug-level message explaining the failure.
 * - The rationale is conservative: a partially-decoded AnalyzerState could produce incorrect symbol lookups or
 *   subtle incorrect behavior. It is safer to rebuild analyzer state from sources than to operate on a possibly
 *   inconsistent snapshot.
 *
 * <p>Design influences
 * - The compact, explicit, versioned-on-disk design is inspired by the pragmatic approach used in other tooling
 *   projects such as Apache Fory: keep an explicit version token, write a small deterministic binary payload, and
 *   always treat unknown or corrupted snapshots as needing reconstruction. This class captures that spirit without
 *   copying any specific implementation details or code from those projects.
 *
 * <p>Developer notes
 * - Where the state lives: Analyzers persist per-language state to the Language.getStoragePath(project) path (for
 *   example, ".brokk/java.bin.gzip"). The AnalyzerWrapper coordinates saving for multi-language analyzers by asking
 *   each Language to save its delegate analyzer state.
 * - How state is invalidated: user-initiated "Refresh Code Intelligence" (requestRebuild) triggers deletion of the
 *   per-language storage files. The AnalyzerWrapper takes a conservative approach: it deletes persisted snapshots and
 *   marks a rebuild request so the next analyzer build will reparse sources from scratch. This ensures that stale on-disk
 *   state does not cause confusing behavior after an explicit refresh.
 *
 * <p>Extending the schema
 * - To add new fields: add new sections to the writer (appending at the end is preferred), bump the schema version,
 *   and add corresponding reader logic. If you need to accept older versions, implement a migration path that branches
 *   on the version token and reconstructs the missing data from other payload fields where possible.
 * - Keep the version check early and fail-fast: do not attempt to interpret the payload when the version token differs.
 */
public final class TreeSitterStateIO {
    private static final Logger log = LoggerFactory.getLogger(TreeSitterStateIO.class);

    // Current schema version token (bump when changing on-disk encoding). Use a semantic-style token.
    private static final String CURRENT_SCHEMA_VERSION = "1.0";

    private TreeSitterStateIO() {}

    /* ================= DTOs (kept as-is from prior design) ================= */

    public record ProjectFileDto(String root, String relPath) {}

    public record CodeUnitDto(
            ProjectFileDto source,
            CodeUnitType kind,
            String packageName,
            String shortName,
            @Nullable String signature) {}

    public record ImportInfoDto(
            String rawSnippet, boolean isWildcard, @Nullable String identifier, @Nullable String alias) {}

    public record AnalyzerStateDto(
            Map<String, List<CodeUnitDto>> symbolIndex,
            List<CodeUnitEntryDto> codeUnitState,
            List<FileStateEntryDto> fileState,
            List<ImportEntryDto> imports,
            List<ReverseImportEntryDto> reverseImports,
            @Nullable List<SupertypeEntryDto> supertypes,
            @Nullable List<SubtypeEntryDto> subtypes,
            List<String> symbolKeys,
            long snapshotEpochNanos) {}

    public record CodeUnitPropertiesDto(
            List<CodeUnitDto> children, List<String> signatures, List<IAnalyzer.Range> ranges, boolean hasBody) {}

    public record CodeUnitEntryDto(CodeUnitDto key, CodeUnitPropertiesDto value) {}

    public record FileStateEntryDto(ProjectFileDto key, FilePropertiesDto value) {}

    public record FilePropertiesDto(
            List<CodeUnitDto> topLevelCodeUnits, List<ImportInfoDto> importStatements, boolean containsTests) {
        public FilePropertiesDto {
            Objects.requireNonNull(topLevelCodeUnits);
            Objects.requireNonNull(importStatements);
        }
    }

    public record ImportEntryDto(ProjectFileDto key, List<CodeUnitDto> value) {}

    public record ReverseImportEntryDto(ProjectFileDto key, List<ProjectFileDto> value) {}

    public record SupertypeEntryDto(CodeUnitDto key, List<CodeUnitDto> value) {}

    public record SubtypeEntryDto(CodeUnitDto key, List<CodeUnitDto> value) {}

    /* ================= Public save/load API ================= */

    @Blocking
    public static void save(TreeSitterAnalyzer.AnalyzerState state, Path file) {
        long startMs = System.currentTimeMillis();
        Path temp = null;
        Path parent = (file.getParent() != null ? file.getParent() : Path.of("."))
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(parent);

            String baseName = file.getFileName().toString();
            String prefix = "." + baseName + ".";
            String suffix = ".tmp";
            temp = Files.createTempFile(parent, prefix, suffix);

            var dto = toDto(state);

            try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(temp))) {
                writeAnalyzerStateDto(dto, gz);
            }

            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException amnse) {
                log.debug("Atomic move not supported for {}; falling back to non-atomic replace with retries", file);
                moveWithRetriesOrCopyFallback(temp, file);
            }

            long durMs = System.currentTimeMillis() - startMs;
            log.debug("Saved TreeSitter AnalyzerState to {} in {} ms (schema {})", file, durMs, CURRENT_SCHEMA_VERSION);
        } catch (IOException e) {
            log.warn("Failed to save TreeSitter AnalyzerState to {}: {}", file, e.getMessage(), e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ex) {
                    log.debug("Failed to delete temp file {} after save failure: {}", temp, ex.getMessage());
                }
            }
        }
    }

    /**
     * Load an AnalyzerState from the provided file using our custom binary format.
     * Returns Optional.empty() if file is missing, incompatible, or deserialization fails.
     *
     * Note: Legacy Jackson-based snapshots that do not include the explicit schema version header are treated as
     * incompatible and will cause this method to return Optional.empty() (so the analyzer is rebuilt).
     */
    @Blocking
    public static Optional<TreeSitterAnalyzer.AnalyzerState> load(Path file) {
        if (!Files.exists(file)) {
            log.debug("Analyzer state file does not exist: {}", file);
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();

        // Primary attempt: our explicit versioned binary format. We require the version header to be present and match.
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(file))) {
            // We need a buffered stream so our readString/readInt helpers work properly
            try (var bis = new java.io.BufferedInputStream(gz)) {
                // Attempt to read schema version header
                String version = null;
                try {
                    version = readString(bis);
                } catch (IOException e) {
                    // Catches both EOFException and other IO problems - missing/malformed header => incompatible
                    // snapshot.
                    log.debug(
                            "Analyzer state at {} missing or malformed schema version header: {}",
                            file,
                            e.getMessage());
                    return Optional.empty();
                }

                if (!CURRENT_SCHEMA_VERSION.equals(version)) {
                    log.debug(
                            "Unsupported AnalyzerState schema version {} in file {}; expected {}. Treating as incompatible.",
                            version,
                            file,
                            CURRENT_SCHEMA_VERSION);
                    return Optional.empty();
                }

                // Version matches; read the remainder of the payload using the stream positioned after the version
                // header.
                AnalyzerStateDto dto = readAnalyzerStateDtoFromStream(bis);
                if (dto == null) {
                    log.debug(
                            "Failed to read AnalyzerStateDto from {} despite matching schema version; treating as incompatible.",
                            file);
                    return Optional.empty();
                }
                var state = fromDto(dto);
                long durMs = System.currentTimeMillis() - startMs;
                log.debug(
                        "Loaded TreeSitter AnalyzerState from {} in {} ms (schema {})",
                        file,
                        durMs,
                        CURRENT_SCHEMA_VERSION);
                return Optional.of(state);
            }
        } catch (IOException e) {
            // Any IO error reading the new, versioned format is treated as incompatibility/corruption.
            log.debug(
                    "Failed to read AnalyzerState (versioned reader) from {} ({}). Will rebuild.",
                    file,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /* ================= Converters (runtime <-> DTO) ================= */

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
            var childrenDtos =
                    props.children().stream().map(TreeSitterStateIO::toDto).toList();

            var propsDto = new CodeUnitPropertiesDto(childrenDtos, props.signatures(), props.ranges(), props.hasBody());

            cuEntries.add(new CodeUnitEntryDto(toDto(e.getKey()), propsDto));
        }

        // fileState -> entries list (omit parsed tree)
        List<FileStateEntryDto> fileEntries = new ArrayList<>(state.fileState().size());
        for (var e : state.fileState().entrySet()) {
            var fileProps = e.getValue();

            var topLevelDtos =
                    new ArrayList<CodeUnitDto>(fileProps.topLevelCodeUnits().size());
            for (var cu : fileProps.topLevelCodeUnits()) topLevelDtos.add(toDto(cu));

            var importDtos = fileProps.importStatements().stream()
                    .map(TreeSitterStateIO::toDto)
                    .toList();

            var fpDto = new FilePropertiesDto(topLevelDtos, importDtos, fileProps.containsTests());
            fileEntries.add(new FileStateEntryDto(toDto(e.getKey()), fpDto));
        }

        // imports -> entries list from ImportGraph
        var forwardImports = state.importGraph().imports();
        List<ImportEntryDto> importEntries = new ArrayList<>(forwardImports.size());
        for (var e : forwardImports.entrySet()) {
            importEntries.add(new ImportEntryDto(
                    toDto(e.getKey()),
                    e.getValue().stream()
                            .map(TreeSitterStateIO::toDto)
                            .sorted(Comparator.comparing(CodeUnitDto::packageName)
                                    .thenComparing(CodeUnitDto::shortName))
                            .toList()));
        }

        // reverseImports -> entries list from ImportGraph
        var reverseImports = state.importGraph().reverseImports();
        List<ReverseImportEntryDto> reverseImportEntries = new ArrayList<>(reverseImports.size());
        for (var e : reverseImports.entrySet()) {
            reverseImportEntries.add(new ReverseImportEntryDto(
                    toDto(e.getKey()),
                    e.getValue().stream()
                            .map(TreeSitterStateIO::toDto)
                            .sorted(Comparator.comparing(ProjectFileDto::relPath))
                            .toList()));
        }

        // typeHierarchy -> DTO from TypeHierarchyGraph
        List<SupertypeEntryDto> supertypeEntries =
                new ArrayList<>(state.typeHierarchyGraph().supertypes().size());
        for (var e : state.typeHierarchyGraph().supertypes().entrySet()) {
            supertypeEntries.add(new SupertypeEntryDto(
                    toDto(e.getKey()),
                    e.getValue().stream().map(TreeSitterStateIO::toDto).toList()));
        }
        List<SubtypeEntryDto> subtypeEntries =
                new ArrayList<>(state.typeHierarchyGraph().subtypes().size());
        for (var e : state.typeHierarchyGraph().subtypes().entrySet()) {
            subtypeEntries.add(new SubtypeEntryDto(
                    toDto(e.getKey()),
                    e.getValue().stream().map(TreeSitterStateIO::toDto).toList()));
        }

        // Symbol keys for the index
        List<String> symbolKeys = new ArrayList<>();
        for (String key : state.symbolKeyIndex().all()) {
            symbolKeys.add(key);
        }

        return new AnalyzerStateDto(
                symbolIndexCopy,
                cuEntries,
                fileEntries,
                importEntries,
                reverseImportEntries,
                // Use empty lists rather than null to preserve round-trip expectations/tests
                (supertypeEntries == null) ? List.<SupertypeEntryDto>of() : supertypeEntries,
                (subtypeEntries == null) ? List.<SubtypeEntryDto>of() : subtypeEntries,
                symbolKeys,
                state.snapshotEpochNanos());
    }

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
                    v.children().stream().map(TreeSitterStateIO::fromDto).toList(),
                    v.signatures(),
                    v.ranges(),
                    v.hasBody());

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

        // Rebuild forward imports Map for ImportGraph
        Map<ProjectFile, Set<CodeUnit>> importsMap = new HashMap<>();
        for (var entry : dto.imports()) {
            importsMap.put(
                    fromDto(entry.key()),
                    entry.value().stream().map(TreeSitterStateIO::fromDto).collect(Collectors.toSet()));
        }

        // Rebuild reverse imports Map for ImportGraph
        Map<ProjectFile, Set<ProjectFile>> reverseImportsMap = new HashMap<>();
        for (var entry : dto.reverseImports()) {
            reverseImportsMap.put(
                    fromDto(entry.key()),
                    entry.value().stream().map(TreeSitterStateIO::fromDto).collect(Collectors.toSet()));
        }

        // Rebuild TypeHierarchyGraph
        Map<CodeUnit, List<CodeUnit>> supertypesMap = new HashMap<>();
        Map<CodeUnit, Set<CodeUnit>> subtypesMap = new HashMap<>();

        var supertypeEntries = dto.supertypes() != null ? dto.supertypes() : List.<SupertypeEntryDto>of();
        for (var entry : supertypeEntries) {
            supertypesMap.put(
                    fromDto(entry.key()),
                    entry.value().stream().map(TreeSitterStateIO::fromDto).toList());
        }

        var subtypeEntries = dto.subtypes() != null ? dto.subtypes() : List.<SubtypeEntryDto>of();
        for (var entry : subtypeEntries) {
            subtypesMap.put(
                    fromDto(entry.key()),
                    entry.value().stream().map(TreeSitterStateIO::fromDto).collect(Collectors.toSet()));
        }

        // Rebuild SymbolKeyIndex
        var keySet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        keySet.addAll(dto.symbolKeys());
        var unmodifiableKeys = Collections.unmodifiableNavigableSet(keySet);
        var symbolKeyIndex = new TreeSitterAnalyzer.SymbolKeyIndex(unmodifiableKeys);

        // Construct new immutable AnalyzerState
        return new TreeSitterAnalyzer.AnalyzerState(
                symbolIndex,
                codeUnitState,
                fileState,
                ImportGraph.from(importsMap, reverseImportsMap),
                TypeHierarchyGraph.from(supertypesMap, subtypesMap),
                symbolKeyIndex,
                dto.snapshotEpochNanos());
    }

    /* ================= Helpers for DTO <-> domain simple conversions ================= */

    private static CodeUnitDto toDto(CodeUnit cu) {
        return new CodeUnitDto(toDto(cu.source()), cu.kind(), cu.packageName(), cu.shortName(), cu.signature());
    }

    private static CodeUnit fromDto(CodeUnitDto dto) {
        return new CodeUnit(fromDto(dto.source()), dto.kind(), dto.packageName(), dto.shortName(), dto.signature());
    }

    private static ProjectFileDto toDto(ProjectFile pf) {
        java.nio.file.Path root = pf.getRoot().toAbsolutePath().normalize();
        java.nio.file.Path rel = pf.getRelPath();

        String relStr;
        if (rel.isAbsolute()) {
            java.nio.file.Path nr = root.toAbsolutePath().normalize();
            java.nio.file.Path rl = rel.toAbsolutePath().normalize();
            if (rl.startsWith(nr)) {
                relStr = nr.relativize(rl).toString();
            } else {
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
        java.nio.file.Path root = parseRootPath(dto.root());
        java.nio.file.Path rel = java.nio.file.Path.of(dto.relPath());

        if (rel.isAbsolute()) {
            java.nio.file.Path nr = root.toAbsolutePath().normalize();
            java.nio.file.Path rl = rel.toAbsolutePath().normalize();
            if (rl.startsWith(nr)) {
                rel = nr.relativize(rl);
            } else {
                log.debug(
                        "Loaded ProjectFileDto.relPath was absolute and outside root; using fileName only: root={}, abs={}",
                        nr,
                        rl);
                java.nio.file.Path fileName = rl.getFileName();
                rel = (fileName != null) ? fileName : java.nio.file.Path.of("");
            }
        }

        return new ProjectFile(root, rel);
    }

    private static java.nio.file.Path parseRootPath(String text) {
        try {
            if (text.startsWith("file:")) {
                URI uri = new URI(text);
                return java.nio.file.Path.of(uri).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // fall through to plain path parsing
        }
        java.nio.file.Path p = java.nio.file.Path.of(text);
        if (!p.isAbsolute()) {
            if (text.startsWith("file:")) {
                String stripped = text.substring("file:".length());
                p = java.nio.file.Path.of(stripped);
            }
            p = p.toAbsolutePath();
        }
        return p.normalize();
    }

    private static void moveWithRetriesOrCopyFallback(Path temp, Path file) throws IOException {
        boolean moved = false;
        IOException lastMoveEx = null;
        for (int attempt = 1; attempt <= 3 && !moved; attempt++) {
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (IOException ioe) {
                lastMoveEx = ioe;
                log.debug("Non-atomic move attempt {}/3 failed for {}: {}", attempt, file, ioe.getMessage());
                try {
                    Thread.sleep(75L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!moved) {
            log.debug("Falling back to copy(REPLACE_EXISTING) for {} after move failures", file);
            try {
                Files.copy(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copyEx) {
                copyEx.addSuppressed(lastMoveEx);
                throw copyEx;
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ex) {
                    log.debug("Failed to delete temp file {} after copy fallback: {}", temp, ex.getMessage());
                }
            }
        }
    }

    /* ================= Binary writer/reader for AnalyzerStateDto ================= */

    // Utility write helpers
    private static void writeInt(java.io.OutputStream os, int v) throws IOException {
        byte[] b = new byte[4];
        b[0] = (byte) (v >>> 24);
        b[1] = (byte) (v >>> 16);
        b[2] = (byte) (v >>> 8);
        b[3] = (byte) v;
        os.write(b);
    }

    private static int readInt(java.io.InputStream is) throws IOException {
        byte[] b = readN(is, 4);
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static void writeLong(java.io.OutputStream os, long v) throws IOException {
        byte[] b = new byte[8];
        b[0] = (byte) (v >>> 56);
        b[1] = (byte) (v >>> 48);
        b[2] = (byte) (v >>> 40);
        b[3] = (byte) (v >>> 32);
        b[4] = (byte) (v >>> 24);
        b[5] = (byte) (v >>> 16);
        b[6] = (byte) (v >>> 8);
        b[7] = (byte) v;
        os.write(b);
    }

    private static long readLong(java.io.InputStream is) throws IOException {
        byte[] b = readN(is, 8);
        return ((long) (b[0] & 0xFF) << 56)
                | ((long) (b[1] & 0xFF) << 48)
                | ((long) (b[2] & 0xFF) << 40)
                | ((long) (b[3] & 0xFF) << 32)
                | ((long) (b[4] & 0xFF) << 24)
                | ((long) (b[5] & 0xFF) << 16)
                | ((long) (b[6] & 0xFF) << 8)
                | ((long) (b[7] & 0xFF));
    }

    private static void writeBoolean(java.io.OutputStream os, boolean v) throws IOException {
        os.write(v ? 1 : 0);
    }

    private static boolean readBoolean(java.io.InputStream is) throws IOException {
        int v = is.read();
        if (v < 0) throw new EOFException();
        return v != 0;
    }

    private static void writeString(java.io.OutputStream os, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeInt(os, bytes.length);
        os.write(bytes);
    }

    private static String readString(java.io.InputStream is) throws IOException {
        int len = readInt(is);
        if (len < 0) throw new IOException("Negative string length");
        if (len == 0) return "";
        byte[] bytes = readN(is, len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readN(java.io.InputStream is, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return buf;
    }

    /* Top-level writer/reader using streams (GZIP handled externally) */

    private static void writeAnalyzerStateDto(AnalyzerStateDto dto, java.io.OutputStream out) throws IOException {
        // Use a buffered wrapper for efficiency
        try (var os = new java.io.BufferedOutputStream(out)) {
            // Write explicit schema version header first
            writeString(os, CURRENT_SCHEMA_VERSION);

            // symbolIndex: Map<String, List<CodeUnitDto>>
            writeInt(os, dto.symbolIndex().size());
            for (var e : dto.symbolIndex().entrySet()) {
                writeString(os, e.getKey());
                List<CodeUnitDto> list = e.getValue();
                writeInt(os, list.size());
                for (var cu : list) writeCodeUnitDto(os, cu);
            }

            // codeUnitState: List<CodeUnitEntryDto>
            writeInt(os, dto.codeUnitState().size());
            for (var entry : dto.codeUnitState()) {
                writeCodeUnitDto(os, entry.key());
                writeCodeUnitPropertiesDto(os, entry.value());
            }

            // fileState: List<FileStateEntryDto>
            writeInt(os, dto.fileState().size());
            for (var entry : dto.fileState()) {
                writeProjectFileDto(os, entry.key());
                writeFilePropertiesDto(os, entry.value());
            }

            // imports
            writeInt(os, dto.imports().size());
            for (var entry : dto.imports()) {
                writeProjectFileDto(os, entry.key());
                writeInt(os, entry.value().size());
                for (var cu : entry.value()) writeCodeUnitDto(os, cu);
            }

            // reverseImports
            writeInt(os, dto.reverseImports().size());
            for (var entry : dto.reverseImports()) {
                writeProjectFileDto(os, entry.key());
                writeInt(os, entry.value().size());
                for (var pf : entry.value()) writeProjectFileDto(os, pf);
            }

            // supertypes (nullable)
            writeBoolean(os, dto.supertypes() != null);
            if (dto.supertypes() != null) {
                writeInt(os, dto.supertypes().size());
                for (var e : dto.supertypes()) {
                    writeCodeUnitDto(os, e.key());
                    writeInt(os, e.value().size());
                    for (var cu : e.value()) writeCodeUnitDto(os, cu);
                }
            }

            // subtypes (nullable)
            writeBoolean(os, dto.subtypes() != null);
            if (dto.subtypes() != null) {
                writeInt(os, dto.subtypes().size());
                for (var e : dto.subtypes()) {
                    writeCodeUnitDto(os, e.key());
                    writeInt(os, e.value().size());
                    for (var cu : e.value()) writeCodeUnitDto(os, cu);
                }
            }

            // symbolKeys
            writeInt(os, dto.symbolKeys().size());
            for (var k : dto.symbolKeys()) writeString(os, k);

            // snapshotEpochNanos
            writeLong(os, dto.snapshotEpochNanos());

            os.flush();
        }
    }

    /**
     * Reads AnalyzerStateDto from a stream where the schema version header has already been consumed.
     */
    private static AnalyzerStateDto readAnalyzerStateDtoFromStream(java.io.InputStream is) throws IOException {
        // symbolIndex
        int symbolMapSize = readInt(is);
        Map<String, List<CodeUnitDto>> symbolIndex = new HashMap<>(Math.max(0, symbolMapSize));
        for (int i = 0; i < symbolMapSize; i++) {
            String key = readString(is);
            int listSize = readInt(is);
            List<CodeUnitDto> list = new ArrayList<>(Math.max(0, listSize));
            for (int j = 0; j < listSize; j++) list.add(readCodeUnitDto(is));
            symbolIndex.put(key, list);
        }

        // codeUnitState
        int cuStateSize = readInt(is);
        List<CodeUnitEntryDto> cuState = new ArrayList<>(Math.max(0, cuStateSize));
        for (int i = 0; i < cuStateSize; i++) {
            var key = readCodeUnitDto(is);
            var value = readCodeUnitPropertiesDto(is);
            cuState.add(new CodeUnitEntryDto(key, value));
        }

        // fileState
        int fileStateSize = readInt(is);
        List<FileStateEntryDto> fileState = new ArrayList<>(Math.max(0, fileStateSize));
        for (int i = 0; i < fileStateSize; i++) {
            var key = readProjectFileDto(is);
            var value = readFilePropertiesDto(is);
            fileState.add(new FileStateEntryDto(key, value));
        }

        // imports
        int importsSize = readInt(is);
        List<ImportEntryDto> imports = new ArrayList<>(Math.max(0, importsSize));
        for (int i = 0; i < importsSize; i++) {
            var key = readProjectFileDto(is);
            int listSize = readInt(is);
            List<CodeUnitDto> list = new ArrayList<>(Math.max(0, listSize));
            for (int j = 0; j < listSize; j++) list.add(readCodeUnitDto(is));
            imports.add(new ImportEntryDto(key, list));
        }

        // reverseImports
        int revImportsSize = readInt(is);
        List<ReverseImportEntryDto> reverseImports = new ArrayList<>(Math.max(0, revImportsSize));
        for (int i = 0; i < revImportsSize; i++) {
            var key = readProjectFileDto(is);
            int listSize = readInt(is);
            List<ProjectFileDto> list = new ArrayList<>(Math.max(0, listSize));
            for (int j = 0; j < listSize; j++) list.add(readProjectFileDto(is));
            reverseImports.add(new ReverseImportEntryDto(key, list));
        }

        // supertypes (nullable)
        boolean hasSupertypes = readBoolean(is);
        List<SupertypeEntryDto> supertypes = null;
        if (hasSupertypes) {
            int stSize = readInt(is);
            supertypes = new ArrayList<>(Math.max(0, stSize));
            for (int i = 0; i < stSize; i++) {
                var key = readCodeUnitDto(is);
                int listSize = readInt(is);
                List<CodeUnitDto> list = new ArrayList<>(Math.max(0, listSize));
                for (int j = 0; j < listSize; j++) list.add(readCodeUnitDto(is));
                supertypes.add(new SupertypeEntryDto(key, list));
            }
        }

        // subtypes (nullable)
        boolean hasSubtypes = readBoolean(is);
        List<SubtypeEntryDto> subtypes = null;
        if (hasSubtypes) {
            int stSize = readInt(is);
            subtypes = new ArrayList<>(Math.max(0, stSize));
            for (int i = 0; i < stSize; i++) {
                var key = readCodeUnitDto(is);
                int listSize = readInt(is);
                List<CodeUnitDto> list = new ArrayList<>(Math.max(0, listSize));
                for (int j = 0; j < listSize; j++) list.add(readCodeUnitDto(is));
                subtypes.add(new SubtypeEntryDto(key, list));
            }
        }

        // symbolKeys
        int symbolKeysSize = readInt(is);
        List<String> symbolKeys = new ArrayList<>(Math.max(0, symbolKeysSize));
        for (int i = 0; i < symbolKeysSize; i++) symbolKeys.add(readString(is));

        // snapshotEpochNanos
        long snapshotEpochNanos = readLong(is);

        return new AnalyzerStateDto(
                symbolIndex,
                cuState,
                fileState,
                imports,
                reverseImports,
                supertypes,
                subtypes,
                symbolKeys,
                snapshotEpochNanos);
    }

    /* ================= DTO primitive writers/readers ================= */

    private static void writeProjectFileDto(java.io.OutputStream os, ProjectFileDto dto) throws IOException {
        writeString(os, dto.root());
        writeString(os, dto.relPath());
    }

    private static ProjectFileDto readProjectFileDto(java.io.InputStream is) throws IOException {
        String root = readString(is);
        String rel = readString(is);
        return new ProjectFileDto(root, rel);
    }

    private static void writeCodeUnitDto(java.io.OutputStream os, CodeUnitDto dto) throws IOException {
        writeProjectFileDto(os, dto.source());
        writeString(os, dto.kind().name());
        writeString(os, dto.packageName());
        writeString(os, dto.shortName());
        writeBoolean(os, dto.signature() != null);
        if (dto.signature() != null) writeString(os, dto.signature());
    }

    private static CodeUnitDto readCodeUnitDto(java.io.InputStream is) throws IOException {
        ProjectFileDto src = readProjectFileDto(is);
        String kindName = readString(is);
        CodeUnitType kind = CodeUnitType.valueOf(kindName);
        String pkg = readString(is);
        String shortName = readString(is);
        boolean hasSignature = readBoolean(is);
        String signature = hasSignature ? readString(is) : null;
        return new CodeUnitDto(src, kind, pkg, shortName, signature);
    }

    private static void writeCodeUnitPropertiesDto(java.io.OutputStream os, CodeUnitPropertiesDto dto)
            throws IOException {
        writeInt(os, dto.children().size());
        for (var c : dto.children()) writeCodeUnitDto(os, c);
        writeInt(os, dto.signatures().size());
        for (var s : dto.signatures()) writeString(os, s);
        writeInt(os, dto.ranges().size());
        for (var r : dto.ranges()) {
            writeInt(os, r.startByte());
            writeInt(os, r.endByte());
            writeInt(os, r.startLine());
            writeInt(os, r.endLine());
            writeInt(os, r.commentStartByte());
        }
        writeBoolean(os, dto.hasBody());
    }

    private static CodeUnitPropertiesDto readCodeUnitPropertiesDto(java.io.InputStream is) throws IOException {
        int childrenSize = readInt(is);
        List<CodeUnitDto> children = new ArrayList<>(Math.max(0, childrenSize));
        for (int i = 0; i < childrenSize; i++) children.add(readCodeUnitDto(is));

        int sigSize = readInt(is);
        List<String> sigs = new ArrayList<>(Math.max(0, sigSize));
        for (int i = 0; i < sigSize; i++) sigs.add(readString(is));

        int rangesSize = readInt(is);
        List<IAnalyzer.Range> ranges = new ArrayList<>(Math.max(0, rangesSize));
        for (int i = 0; i < rangesSize; i++) {
            int startByte = readInt(is);
            int endByte = readInt(is);
            int startLine = readInt(is);
            int endLine = readInt(is);
            int commentStart = readInt(is);
            ranges.add(new IAnalyzer.Range(startByte, endByte, startLine, endLine, commentStart));
        }

        boolean hasBody = readBoolean(is);
        return new CodeUnitPropertiesDto(children, sigs, ranges, hasBody);
    }

    private static void writeFilePropertiesDto(java.io.OutputStream os, FilePropertiesDto dto) throws IOException {
        writeInt(os, dto.topLevelCodeUnits().size());
        for (var c : dto.topLevelCodeUnits()) writeCodeUnitDto(os, c);
        writeInt(os, dto.importStatements().size());
        for (var im : dto.importStatements()) writeImportInfoDto(os, im);
        writeBoolean(os, dto.containsTests());
    }

    private static FilePropertiesDto readFilePropertiesDto(java.io.InputStream is) throws IOException {
        int topLevelSize = readInt(is);
        List<CodeUnitDto> topLevel = new ArrayList<>(Math.max(0, topLevelSize));
        for (int i = 0; i < topLevelSize; i++) topLevel.add(readCodeUnitDto(is));

        int importsSize = readInt(is);
        List<ImportInfoDto> imports = new ArrayList<>(Math.max(0, importsSize));
        for (int i = 0; i < importsSize; i++) imports.add(readImportInfoDto(is));

        boolean containsTests = readBoolean(is);
        return new FilePropertiesDto(topLevel, imports, containsTests);
    }

    private static void writeImportInfoDto(java.io.OutputStream os, ImportInfoDto dto) throws IOException {
        writeString(os, dto.rawSnippet());
        writeBoolean(os, dto.isWildcard());
        writeBoolean(os, dto.identifier() != null);
        if (dto.identifier() != null) writeString(os, dto.identifier());
        writeBoolean(os, dto.alias() != null);
        if (dto.alias() != null) writeString(os, dto.alias());
    }

    private static ImportInfoDto readImportInfoDto(java.io.InputStream is) throws IOException {
        String raw = readString(is);
        boolean isWildcard = readBoolean(is);
        boolean hasIdent = readBoolean(is);
        String ident = hasIdent ? readString(is) : null;
        boolean hasAlias = readBoolean(is);
        String alias = hasAlias ? readString(is) : null;
        return new ImportInfoDto(raw, isWildcard, ident, alias);
    }
}
