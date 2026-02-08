package ai.brokk.analyzer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
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
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistence helper for TreeSitterAnalyzer.AnalyzerState using Jackson Smile.
 *
 * Serializes AnalyzerState into DTOs:
 * - PMap fields are represented as standard Maps or entry lists
 * - SymbolKeyIndex becomes List<String> (keys)
 * - FileProperties omits the parsed TSTree
 * - ProjectFile is serialized via a DTO that guarantees a relative relPath
 */
public final class TreeSitterStateIO {
    private static final Logger log = LoggerFactory.getLogger(TreeSitterStateIO.class);

    /**
     * Snapshot schema version. Increment this when you change the on-disk layout.
     * Consumers of on-disk snapshots should consult this value to decide how to migrate/load.
     */
    public static final String SCHEMA_VERSION = "ai.brokk.treesitter.snapshot.v1";

    // Dedicated Smile ObjectMapper.
    private static final ObjectMapper SMILE_MAPPER =
            new ObjectMapper(new SmileFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        // Ensure nested CodeUnit/ProjectFile anywhere in the object graph (e.g., inside CodeUnitProperties)
        // are serialized/deserialized via our relative-path-safe format.
        SimpleModule module = new SimpleModule("TreeSitterStateIOModule");
        module.addSerializer(CodeUnit.class, new CodeUnitJsonSerializer());
        module.addDeserializer(CodeUnit.class, new CodeUnitJsonDeserializer());
        module.addSerializer(ProjectFile.class, new ProjectFileJsonSerializer());
        module.addDeserializer(ProjectFile.class, new ProjectFileJsonDeserializer());
        SMILE_MAPPER.registerModule(module);
    }

    private TreeSitterStateIO() {}

    /* ================= Jackson adapters for nested types ================= */

    /**
     * Serialize ProjectFile as a minimal DTO with a guaranteed relative relPath.
     */
    static final class ProjectFileJsonSerializer extends JsonSerializer<ProjectFile> {
        @Override
        public void serialize(ProjectFile value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            Path root = value.getRoot().toAbsolutePath().normalize();
            Path rel = value.getRelPath();

            String relStr;
            if (rel.isAbsolute()) {
                Path nr = root.toAbsolutePath().normalize();
                Path rl = rel.toAbsolutePath().normalize();
                if (rl.startsWith(nr)) {
                    relStr = nr.relativize(rl).toString();
                } else {
                    // best-effort fallback; use just the file name to keep it relative
                    relStr = rl.getFileName().toString();
                    log.debug(
                            "ProjectFile relPath was absolute and outside root; falling back to fileName only: root={}, abs={}",
                            nr,
                            rl);
                }
            } else {
                relStr = rel.toString();
            }

            gen.writeStartObject();
            gen.writeStringField("root", root.toString()); // absolute, normalized
            gen.writeStringField("relPath", relStr); // guaranteed relative
            gen.writeEndObject();
        }
    }

    /**
     * Deserialize ProjectFile from minimal DTO, sanitizing absolute relPaths.
     */
    static final class ProjectFileJsonDeserializer extends JsonDeserializer<ProjectFile> {
        @Override
        public @Nullable ProjectFile deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            JsonNode rootNode = node.get("root");
            JsonNode relNode = node.get("relPath");

            if (rootNode == null || relNode == null) {
                ctxt.reportInputMismatch(ProjectFile.class, "Missing required fields for ProjectFile (root, relPath)");
                return null; // Unreachable, reportInputMismatch always throws
            }

            Path root = parseRootPath(rootNode.asText());
            Path rel = Path.of(relNode.asText());

            if (rel.isAbsolute()) {
                Path nr = root.toAbsolutePath().normalize();
                Path rl = rel.toAbsolutePath().normalize();
                if (rl.startsWith(nr)) {
                    rel = nr.relativize(rl);
                } else {
                    log.debug(
                            "Loaded ProjectFile relPath was absolute and outside root; using fileName only: root={}, abs={}",
                            nr,
                            rl);
                    Path fileName = rl.getFileName();
                    rel = (fileName != null) ? fileName : Path.of("");
                }
            }

            return new ProjectFile(root, rel);
        }
    }

    /**
     * Serialize CodeUnit to a minimal DTO, delegating ProjectFile to its serializer.
     */
    static final class CodeUnitJsonSerializer extends JsonSerializer<CodeUnit> {
        @Override
        public void serialize(CodeUnit value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeFieldName("source");
            serializers.defaultSerializeValue(value.source(), gen); // uses ProjectFileJsonSerializer
            gen.writeStringField("kind", value.kind().name());
            gen.writeStringField("packageName", value.packageName());
            gen.writeStringField("shortName", value.shortName());
            if (value.signature() != null) {
                gen.writeStringField("signature", value.signature());
            }
            gen.writeEndObject();
        }
    }

    /**
     * Deserialize CodeUnit from the minimal DTO shape.
     */
    static final class CodeUnitJsonDeserializer extends JsonDeserializer<CodeUnit> {
        @Override
        public @Nullable CodeUnit deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);

            // Source ProjectFile
            JsonNode sourceNode = node.get("source");
            if (sourceNode == null) {
                ctxt.reportInputMismatch(CodeUnit.class, "Missing CodeUnit.source");
                return null; // Unreachable, reportInputMismatch always throws
            }
            ProjectFile source = p.getCodec().treeToValue(sourceNode, ProjectFile.class);

            // Required fields
            JsonNode kindNode = node.get("kind");
            JsonNode pkgNode = node.get("packageName");
            JsonNode shortNode = node.get("shortName");

            if (kindNode == null || pkgNode == null || shortNode == null) {
                ctxt.reportInputMismatch(
                        CodeUnit.class, "Missing required fields for CodeUnit (kind, packageName, shortName)");
                return null; // Unreachable, reportInputMismatch always throws
            }

            CodeUnitType kind = CodeUnitType.valueOf(kindNode.asText());
            String pkg = pkgNode.asText();
            String shortName = shortNode.asText();

            // Optional signature
            JsonNode sigNode = node.get("signature");
            String signature = sigNode != null && !sigNode.isNull() ? sigNode.asText() : null;

            return new CodeUnit(source, kind, pkg, shortName, signature);
        }
    }

    /* ================= DTOs ================= */

    /**
     * A minimal, serialization-safe representation of ProjectFile that
     * enforces that relPath is stored as a relative string.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectFileDto(String root, String relPath) {}

    /**
     * A serialization-safe representation of CodeUnit using ProjectFileDto.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeUnitDto(
            ProjectFileDto source,
            CodeUnitType kind,
            String packageName,
            String shortName,
            @Nullable String signature) {}

    /**
     * DTO for structured import information.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportInfoDto(
            String rawSnippet, boolean isWildcard, @Nullable String identifier, @Nullable String alias) {}

    /**
     * DTO for AnalyzerState with only serializable components.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
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
     * DTO for CodeUnitProperties.
     *
     * <p>Note: signatures were removed from the persisted CodeUnitProperties shape. This layer
     * ignores unknown properties (like legacy signatures) to maintain compatibility with
     * older snapshots.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeUnitPropertiesDto(List<CodeUnitDto> children, List<IAnalyzer.Range> ranges, boolean hasBody) {}

    /**
     * DTO entry for CodeUnit -> CodeUnitProperties maps.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeUnitEntryDto(CodeUnitDto key, CodeUnitPropertiesDto value) {}

    /**
     * DTO entry for ProjectFile -> FileProperties maps.
     * FilePropertiesDto omits the parsed tree.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileStateEntryDto(ProjectFileDto key, FilePropertiesDto value) {}

    /**
     * DTO for TreeSitterAnalyzer.FileProperties without the TSTree.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilePropertiesDto(
            List<CodeUnitDto> topLevelCodeUnits, List<ImportInfoDto> importStatements, boolean containsTests) {
        @JsonCreator
        public FilePropertiesDto(
                @JsonProperty("topLevelCodeUnits") List<CodeUnitDto> topLevelCodeUnits,
                @JsonProperty("importStatements") List<ImportInfoDto> importStatements,
                @JsonProperty(value = "containsTests", required = true) boolean containsTests) {
            this.topLevelCodeUnits = topLevelCodeUnits;
            this.importStatements = importStatements;
            this.containsTests = containsTests;
        }
    }

    /**
     * DTO entry for ProjectFile -> Set<CodeUnit> (imports).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportEntryDto(ProjectFileDto key, List<CodeUnitDto> value) {}

    /**
     * DTO entry for ProjectFile -> Set<ProjectFile> (reverse imports).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReverseImportEntryDto(ProjectFileDto key, List<ProjectFileDto> value) {}

    /**
     * DTO entry for CodeUnit -> List<CodeUnit> (supertypes).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupertypeEntryDto(CodeUnitDto key, List<CodeUnitDto> value) {}

    /**
     * DTO entry for CodeUnit -> Set<CodeUnit> (subtypes).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubtypeEntryDto(CodeUnitDto key, List<CodeUnitDto> value) {}

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

            var dto = toTopLevelDto(state, cacheSnapshot);
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
                SMILE_MAPPER.writeValue(out, dto);
            }

            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException amnse) {
                log.debug("Atomic move not supported for {}; falling back to non-atomic replace with retries", file);
                moveWithRetriesOrCopyFallback(temp, file);
            }

            long durMs = System.currentTimeMillis() - startMs;
            log.debug("Saved TreeSitter Snapshot (state + cache view) to {} in {} ms", file, durMs);
        } catch (IOException e) {
            log.warn("Failed to save TreeSitter Snapshot to {}: {}", file, e.getMessage(), e);
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
     * Backwards-compatible overload for callers that only provide AnalyzerState (no cache snapshot).
     */
    @Blocking
    public static void save(TreeSitterAnalyzer.AnalyzerState state, Path file) {
        save(state, new ai.brokk.analyzer.cache.AnalyzerCache().snapshot(), file);
    }

    /**
     * Load an AnalyzerState from the provided file in Smile format.
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
    @Blocking
    public static Optional<TreeSitterAnalyzer.AnalyzerState> load(Path file) {
        if (!Files.exists(file)) {
            log.debug("Analyzer state file does not exist: {}", file);
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();
        try {
            try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                var top = SMILE_MAPPER.readValue(in, SnapshotDto.class);

                // Handle legacy snapshots that were stored as raw AnalyzerStateDto (not wrapped in SnapshotDto).
                // When Jackson deserializes such data into SnapshotDto, all fields will be null or default.
                if (top.analyzerState() == null) {
                    log.debug(
                            "Snapshot at {} appears to be legacy format (no analyzerState field). Will rebuild.", file);
                    return Optional.empty();
                }

                if (!SCHEMA_VERSION.equals(top.schemaVersion())) {
                    log.debug(
                            "Snapshot schemaVersion mismatch: expected={}, found={}. Will attempt best-effort load of state portion.",
                            SCHEMA_VERSION,
                            top.schemaVersion());
                }
                var state = fromDto(top.analyzerState());
                long durMs = System.currentTimeMillis() - startMs;
                log.debug(
                        "Loaded TreeSitter AnalyzerState from {} (schema={}) in {} ms",
                        file,
                        top.schemaVersion(),
                        durMs);
                return Optional.of(state);
            }
        } catch (ZipException | EOFException e) {
            log.debug("Analyzer state at {} is corrupt or truncated; will rebuild ({}).", file, e.getMessage());
            return Optional.empty();
        } catch (MismatchedInputException mie) {
            log.debug("Analyzer state at {} appears incompatible ({}). Will rebuild analyzer.", file, mie.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.debug("Failed to load TreeSitter AnalyzerState from {} ({}). Will rebuild.", file, e.getMessage());
            return Optional.empty();
        }
    }

    /* ================= Converters ================= */

    /**
     * Top-level Snapshot DTO that will be written to disk. Includes a schemaVersion so future migrations can alter
     * behavior based on that version.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotDto(
            String schemaVersion, AnalyzerStateDto analyzerState, @Nullable CacheSnapshotDto cacheSnapshot) {}

    /**
     * Serializable view of AnalyzerCache.CacheSnapshot. Note: not all cache contents are persisted;
     * some items (e.g., TSTree) are intentionally omitted because they are not easily serializable or safe to persist.
     * The goal is to allow transfer of presentation-heavy but safe data such as signatures and raw supertypes,
     * and forward mappings for imports/typeHierarchy.
     *
     * Note: Jackson does not support complex POJOs as map keys by default when deserializing. To avoid requiring
     * a Map Key deserializer for CodeUnitDto, we represent signature/rawSupertypes maps as explicit entry lists
     * (pairs) instead of Map<CodeUnitDto, ...>.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CacheSnapshotDto(
            List<SignatureEntryDto> signatures,
            List<RawSupertypesEntryDto> rawSupertypes,
            List<ImportEntryDto> importsForward,
            List<SupertypeEntryDto> typeHierarchyForward) {}

    /**
     * Entry DTO for signatures: (CodeUnitDto key) -> List<String> value.
     * Used to avoid using complex objects as Map keys.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignatureEntryDto(CodeUnitDto key, List<String> value) {}

    /**
     * Entry DTO for raw supertypes map: (CodeUnitDto key) -> List<String> value.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawSupertypesEntryDto(CodeUnitDto key, List<String> value) {}

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

            var propsDto = new CodeUnitPropertiesDto(childrenDtos, props.ranges(), props.hasBody());

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
     * Convert a cache snapshot into a serializable DTO.
     */
    public static CacheSnapshotDto cacheSnapshotToDto(ai.brokk.analyzer.cache.AnalyzerCache.CacheSnapshot snapshot) {
        // Convert signatures map into a list of SignatureEntryDto to avoid complex map keys.
        List<SignatureEntryDto> signatures = new ArrayList<>();
        snapshot.signatures()
                .forEach((cu, sigs) -> signatures.add(new SignatureEntryDto(toDto(cu), List.copyOf(sigs))));

        // Convert raw supertypes map into a list of RawSupertypesEntryDto.
        List<RawSupertypesEntryDto> rawSupertypes = new ArrayList<>();
        snapshot.rawSupertypes()
                .forEach((cu, supers) -> rawSupertypes.add(new RawSupertypesEntryDto(toDto(cu), List.copyOf(supers))));

        List<ImportEntryDto> importsForward = new ArrayList<>();
        snapshot.imports()
                .forEachForward((file, units) -> importsForward.add(new ImportEntryDto(
                        toDto(file),
                        units.stream().map(TreeSitterStateIO::toDto).toList())));

        List<SupertypeEntryDto> typeForward = new ArrayList<>();
        snapshot.typeHierarchy()
                .forEachForward((cu, supers) -> typeForward.add(new SupertypeEntryDto(
                        toDto(cu), supers.stream().map(TreeSitterStateIO::toDto).toList())));

        return new CacheSnapshotDto(signatures, rawSupertypes, importsForward, typeForward);
    }

    /**
     * Produce the top-level SnapshotDto combining the AnalyzerState DTO and an optional
     * serializable cache snapshot. Centralizes the schemaVersion assignment so future
     * migrations can gate behavior based on this single constant.
     */
    public static SnapshotDto toTopLevelDto(
            TreeSitterAnalyzer.AnalyzerState state, ai.brokk.analyzer.cache.AnalyzerCache.CacheSnapshot cacheSnapshot) {
        CacheSnapshotDto csd = cacheSnapshot != null ? cacheSnapshotToDto(cacheSnapshot) : null;
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
