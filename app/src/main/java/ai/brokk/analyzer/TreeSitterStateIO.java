package ai.brokk.analyzer;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.apache.fory.Fory;
import org.apache.fory.exception.ForyException;
import org.apache.fory.io.ForyInputStream;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistence helper for TreeSitterAnalyzer.AnalyzerState using Apache Fory for compact binary
 * serialization.
 *
 * <p>Design notes:
 * - We persist a small versioned root wrapper:
 *   AnalyzerStateRoot { String version; AnalyzerStateDto payload; }
 * - The payload is the same DTO layout used previously; migration and backwards-compatibility logic
 *   is implemented in load(): files with missing/incorrect version are treated as incompatible.
 * - Payload serialization is done with Fory.serializeJavaObject(...) into a GZIPOutputStream.
 */
public final class TreeSitterStateIO {
    private static final Logger log = LoggerFactory.getLogger(TreeSitterStateIO.class);

    /**
     * Analyzer state on-disk schema version.
     */
    public static final String FORMAT_VERSION = "1";

    private TreeSitterStateIO() {}

    /* ================= Versioned root ================= */

    public record AnalyzerStateRoot(String version, AnalyzerStateDto payload) {}

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
     */
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

    /**
     * DTO for CodeUnitProperties that can be easily serialized.
     */
    public record CodeUnitPropertiesDto(
            List<CodeUnitDto> children, List<String> signatures, List<IAnalyzer.Range> ranges, boolean hasBody) {}

    /**
     * DTO entry for CodeUnit -> CodeUnitProperties maps.
     */
    public record CodeUnitEntryDto(CodeUnitDto key, CodeUnitPropertiesDto value) {}

    /**
     * DTO entry for ProjectFile -> FileProperties maps.
     * FilePropertiesDto omits the parsed tree.
     */
    public record FileStateEntryDto(ProjectFileDto key, FilePropertiesDto value) {}

    /**
     * DTO for TreeSitterAnalyzer.FileProperties without the TSTree.
     */
    public record FilePropertiesDto(
            List<CodeUnitDto> topLevelCodeUnits, List<ImportInfoDto> importStatements, boolean containsTests) {}

    /**
     * DTO entry for ProjectFile -> Set<CodeUnit> (imports).
     */
    public record ImportEntryDto(ProjectFileDto key, List<CodeUnitDto> value) {}

    /**
     * DTO entry for ProjectFile -> Set<ProjectFile> (reverse imports).
     */
    public record ReverseImportEntryDto(ProjectFileDto key, List<ProjectFileDto> value) {}

    /**
     * DTO entry for CodeUnit -> List<CodeUnit> (supertypes).
     */
    public record SupertypeEntryDto(CodeUnitDto key, List<CodeUnitDto> value) {}

    /**
     * DTO entry for CodeUnit -> Set<CodeUnit> (subtypes).
     */
    public record SubtypeEntryDto(CodeUnitDto key, List<CodeUnitDto> value) {}

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

            AnalyzerStateDto dto = toDto(state);
            AnalyzerStateRoot root = new AnalyzerStateRoot(FORMAT_VERSION, dto);
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
                Fory fory = Fory.builder().requireClassRegistration(false).build();
                fory.serializeJavaObject(out, root);
            }

            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException amnse) {
                log.debug("Atomic move not supported for {}; falling back to non-atomic replace with retries", file);
                moveWithRetriesOrCopyFallback(temp, file);
            }

            long durMs = System.currentTimeMillis() - startMs;
            log.debug("Saved TreeSitter AnalyzerState to {} in {} ms", file, durMs);
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
     * Load an AnalyzerState from the provided file.
     * Returns Optional.empty() if file is missing or deserialization fails.
     */
    @Blocking
    public static Optional<TreeSitterAnalyzer.AnalyzerState> load(Path file) {
        if (!Files.exists(file)) {
            log.debug("Analyzer state file does not exist: {}", file);
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();
        try {
            try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                Fory fory = Fory.builder().requireClassRegistration(false).build();
                // Wrap the InputStream in ForyInputStream so we can use Fory.deserializeJavaObject(ForyInputStream,
                // Class)
                ForyInputStream fin = new ForyInputStream(in);
                AnalyzerStateRoot root = fory.deserializeJavaObject(fin, AnalyzerStateRoot.class);

                if (root == null || root.version() == null || !FORMAT_VERSION.equals(root.version())) {
                    log.debug(
                            "Analyzer state at {} has incompatible or missing version '{}'; expected '{}'. Will rebuild analyzer.",
                            file,
                            (root == null ? "null" : root.version()),
                            FORMAT_VERSION);
                    return Optional.empty();
                }

                TreeSitterAnalyzer.AnalyzerState state = fromDto(root.payload());
                long durMs = System.currentTimeMillis() - startMs;
                log.debug("Loaded TreeSitter AnalyzerState from {} in {} ms", file, durMs);
                return Optional.of(state);
            }
        } catch (ZipException | EOFException e) {
            log.debug("Analyzer state at {} is corrupt or truncated; will rebuild ({}).", file, e.getMessage());
            return Optional.empty();
        } catch (ForyException fe) {
            log.debug(
                    "Analyzer state at {} appears incompatible or corrupt ({}). Will rebuild analyzer.",
                    file,
                    fe.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.debug("Failed to load TreeSitter AnalyzerState from {} ({}). Will rebuild.", file, e.getMessage());
            return Optional.empty();
        }
    }

    /* ================= Converters ================= */

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
                    e.getValue().stream()
                            .map(TreeSitterStateIO::toDto)
                            .sorted(Comparator.comparing(CodeUnitDto::packageName)
                                    .thenComparing(CodeUnitDto::shortName))
                            .toList()));
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
                supertypeEntries,
                subtypeEntries,
                symbolKeys,
                state.snapshotEpochNanos());
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

    /* ================= Helpers ================= */

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
}
