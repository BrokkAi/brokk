package ai.brokk.analyzer;

import ai.brokk.util.Version.SemVer;
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
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
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
 *
 * Adds schema versioning for AnalyzerState snapshots so upgrades can be handled.
 */
public final class TreeSitterStateIO {
    private static final Logger log = LoggerFactory.getLogger(TreeSitterStateIO.class);

    // Current analyzer snapshot schema version. Bump MAJOR for incompatible changes.
    static final SemVer CURRENT_SCHEMA = SemVer.parse("1.0.0");

    // Dedicated Smile ObjectMapper
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
     *
     * NOTE: schemaVersion is optional for backward compatibility. Older snapshots that lack the field
     * will still deserialize; in that case we treat the snapshot as "unversioned" and accept it for now.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalyzerStateDto(
            Map<String, List<CodeUnitDto>> symbolIndex,
            List<CodeUnitEntryDto> codeUnitState,
            List<FileStateEntryDto> fileState,
            List<String> symbolKeys,
            long snapshotEpochNanos,
            @Nullable String schemaVersion) {

        /**
         * Backwards-compatible constructor for callers that do not provide schemaVersion.
         * Delegates to the canonical constructor with schemaVersion = null.
         */
        public AnalyzerStateDto(
                Map<String, List<CodeUnitDto>> symbolIndex,
                List<CodeUnitEntryDto> codeUnitState,
                List<FileStateEntryDto> fileState,
                List<String> symbolKeys,
                long snapshotEpochNanos) {
            this(symbolIndex, codeUnitState, fileState, symbolKeys, snapshotEpochNanos, null);
        }

        @JsonCreator
        public AnalyzerStateDto(
                @JsonProperty("symbolIndex") Map<String, List<CodeUnitDto>> symbolIndex,
                @JsonProperty("codeUnitState") List<CodeUnitEntryDto> codeUnitState,
                @JsonProperty("fileState") List<FileStateEntryDto> fileState,
                @JsonProperty("symbolKeys") List<String> symbolKeys,
                @JsonProperty("snapshotEpochNanos") long snapshotEpochNanos,
                @JsonProperty("schemaVersion") @Nullable String schemaVersion) {
            this.symbolIndex = symbolIndex;
            this.codeUnitState = codeUnitState;
            this.fileState = fileState;
            this.symbolKeys = symbolKeys;
            this.snapshotEpochNanos = snapshotEpochNanos;
            this.schemaVersion = schemaVersion;
        }
    }

    /**
     * DTO for CodeUnitProperties that can be easily serialized.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeUnitPropertiesDto(
            List<CodeUnitDto> children, List<String> signatures, List<IAnalyzer.Range> ranges, boolean hasBody) {}

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
            try (var os = Files.newOutputStream(temp);
                    var out = new LZ4FrameOutputStream(os)) {
                SMILE_MAPPER.writeValue(out, dto);
            }

            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

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
     * Load an AnalyzerState from the provided file in Smile format.
     * Returns Optional.empty() if file is missing or deserialization fails.
     *
     * Version semantics:
     * - If the DTO contains no schemaVersion field (legacy snapshots), accept for now and proceed as-is.
     * - If schemaVersion.major != CURRENT_SCHEMA.major -> incompatible: return Optional.empty().
     * - If minor/patch differ, accept for now; migrate(dto, from, to) is invoked and currently no-ops.
     */
    @Blocking
    public static Optional<TreeSitterAnalyzer.AnalyzerState> load(Path file) {
        if (!Files.exists(file)) {
            log.debug("Analyzer state file does not exist: {}", file);
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();

        try (var in = new LZ4FrameInputStream(Files.newInputStream(file))) {
            return loadFromStream(in, file, startMs);
        } catch (EOFException e) {
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

    private static Optional<TreeSitterAnalyzer.AnalyzerState> loadFromStream(InputStream in, Path file, long startMs)
            throws IOException {
        JsonNode root = SMILE_MAPPER.readTree(in);

        // Deserialize the canonical DTO (ignoring unknown fields)
        var dto = SMILE_MAPPER.treeToValue(root, AnalyzerStateDto.class);

        // Interpret schema version field (backwards-compatible)
        SemVer fromVer;
        if (dto.schemaVersion() == null) {
            log.debug("Loaded AnalyzerState snapshot without schemaVersion; treating as legacy and accepting.");
            fromVer = CURRENT_SCHEMA;
        } else {
            fromVer = SemVer.parse(dto.schemaVersion());
        }

        // If major versions differ, snapshot is incompatible
        if (fromVer.major() != CURRENT_SCHEMA.major()) {
            log.info(
                    "Analyzer snapshot at {} has incompatible schema version {} (current {}). Ignoring snapshot and will rebuild.",
                    file,
                    fromVer,
                    CURRENT_SCHEMA);
            return Optional.empty();
        }

        // Allow minor/patch differences for now; provide a migrate hook
        var migratedDto = migrate(dto, fromVer, CURRENT_SCHEMA);

        // Parse optional legacy graphs if present in the raw JSON
        Map<CodeUnit, List<CodeUnit>> legacySupertypes = parseLegacySupertypes(root.get("supertypes"));
        Map<CodeUnit, Set<CodeUnit>> legacySubtypes = parseLegacySubtypes(root.get("subtypes"));
        Map<ProjectFile, Set<CodeUnit>> legacyImports = parseLegacyImports(root.get("imports"));
        Map<ProjectFile, Set<ProjectFile>> legacyReverseImports = parseLegacyReverseImports(root.get("reverseImports"));

        var state = fromDto(migratedDto, legacySupertypes, legacySubtypes, legacyImports, legacyReverseImports);
        long durMs = System.currentTimeMillis() - startMs;
        log.debug("Loaded TreeSitter AnalyzerState from {} in {} ms (schema {})", file, durMs, fromVer);
        return Optional.of(state);
    }

    /**
     * Parse legacy supertypes array shape into a map CodeUnit -> List<CodeUnit>.
     * Expected element shape: { "key": <CodeUnitDto>, "value": [<CodeUnitDto>, ...] }
     */
    private static Map<CodeUnit, List<CodeUnit>> parseLegacySupertypes(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) return Map.of();
        Map<CodeUnit, List<CodeUnit>> out = new HashMap<>();
        for (JsonNode entry : node) {
            JsonNode keyNode = entry.get("key");
            JsonNode valueNode = entry.get("value");
            if (keyNode == null || valueNode == null || !valueNode.isArray()) continue;
            try {
                var keyDto = SMILE_MAPPER.treeToValue(keyNode, CodeUnitDto.class);
                if (keyDto == null) continue;
                CodeUnit key = fromDto(keyDto);

                List<CodeUnit> vals = new ArrayList<>();
                for (JsonNode v : valueNode) {
                    var cdto = SMILE_MAPPER.treeToValue(v, CodeUnitDto.class);
                    if (cdto != null) vals.add(fromDto(cdto));
                }
                out.put(key, vals);
            } catch (IOException e) {
                log.debug("Failed to parse legacy supertype entry: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * Parse legacy subtypes array shape into a map CodeUnit -> Set<CodeUnit>.
     */
    private static Map<CodeUnit, Set<CodeUnit>> parseLegacySubtypes(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) return Map.of();
        Map<CodeUnit, Set<CodeUnit>> out = new HashMap<>();
        for (JsonNode entry : node) {
            JsonNode keyNode = entry.get("key");
            JsonNode valueNode = entry.get("value");
            if (keyNode == null || valueNode == null || !valueNode.isArray()) continue;
            try {
                var keyDto = SMILE_MAPPER.treeToValue(keyNode, CodeUnitDto.class);
                if (keyDto == null) continue;
                CodeUnit key = fromDto(keyDto);
                Set<CodeUnit> vals = new HashSet<>();
                for (JsonNode v : valueNode) {
                    var cdto = SMILE_MAPPER.treeToValue(v, CodeUnitDto.class);
                    if (cdto != null) vals.add(fromDto(cdto));
                }
                out.put(key, vals);
            } catch (IOException e) {
                log.debug("Failed to parse legacy subtype entry: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * Parse legacy imports array shape into a map ProjectFile -> Set<CodeUnit>.
     * Expected entry shape: { "key": <ProjectFileDto>, "value": [<CodeUnitDto>, ...] }
     */
    private static Map<ProjectFile, Set<CodeUnit>> parseLegacyImports(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) return Map.of();
        Map<ProjectFile, Set<CodeUnit>> out = new HashMap<>();
        for (JsonNode entry : node) {
            JsonNode keyNode = entry.get("key");
            JsonNode valueNode = entry.get("value");
            if (keyNode == null || valueNode == null || !valueNode.isArray()) continue;
            try {
                var pfdto = SMILE_MAPPER.treeToValue(keyNode, ProjectFileDto.class);
                if (pfdto == null) continue;
                ProjectFile key = fromDto(pfdto);
                Set<CodeUnit> vals = new HashSet<>();
                for (JsonNode v : valueNode) {
                    var cdto = SMILE_MAPPER.treeToValue(v, CodeUnitDto.class);
                    if (cdto != null) vals.add(fromDto(cdto));
                }
                out.put(key, vals);
            } catch (IOException e) {
                log.debug("Failed to parse legacy import entry: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * Parse legacy reverseImports array shape into a map ProjectFile -> Set<ProjectFile>.
     */
    private static Map<ProjectFile, Set<ProjectFile>> parseLegacyReverseImports(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) return Map.of();
        Map<ProjectFile, Set<ProjectFile>> out = new HashMap<>();
        for (JsonNode entry : node) {
            JsonNode keyNode = entry.get("key");
            JsonNode valueNode = entry.get("value");
            if (keyNode == null || valueNode == null || !valueNode.isArray()) continue;
            try {
                var pfdto = SMILE_MAPPER.treeToValue(keyNode, ProjectFileDto.class);
                if (pfdto == null) continue;
                ProjectFile key = fromDto(pfdto);
                Set<ProjectFile> vals = new HashSet<>();
                for (JsonNode v : valueNode) {
                    var pfd = SMILE_MAPPER.treeToValue(v, ProjectFileDto.class);
                    if (pfd != null) vals.add(fromDto(pfd));
                }
                out.put(key, vals);
            } catch (IOException e) {
                log.debug("Failed to parse legacy reverse import entry: {}", e.getMessage());
            }
        }
        return out;
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

        // Note: ImportGraph and TypeHierarchyGraph are treated as cache data and are no longer
        // serialized as authoritative state. They can be reconstructed lazily by AnalyzerCache
        // from the persisted symbol/codeunit/file structures on analyzer instantiation or update.

        // Symbol keys for the index
        List<String> symbolKeys = new ArrayList<>();
        for (String key : state.symbolKeyIndex().all()) {
            symbolKeys.add(key);
        }

        return new AnalyzerStateDto(
                symbolIndexCopy,
                cuEntries,
                fileEntries,
                symbolKeys,
                state.snapshotEpochNanos(),
                CURRENT_SCHEMA.toString());
    }

    /**
     * Convert DTO back into an immutable AnalyzerState snapshot.
     */
    public static TreeSitterAnalyzer.AnalyzerState fromDto(AnalyzerStateDto dto) {
        return fromDto(dto, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Internal helper to rebuild AnalyzerState from DTO plus optional legacy graph data.
     */
    public static TreeSitterAnalyzer.AnalyzerState fromDto(
            AnalyzerStateDto dto,
            Map<CodeUnit, List<CodeUnit>> legacySupertypes,
            Map<CodeUnit, Set<CodeUnit>> legacySubtypes,
            Map<ProjectFile, Set<CodeUnit>> legacyImports,
            Map<ProjectFile, Set<ProjectFile>> legacyReverseImports) {

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

        // Use provided legacy graphs when available; otherwise treat them as empty cache data.
        ImportGraph importGraph = (legacyImports.isEmpty() && legacyReverseImports.isEmpty())
                ? ImportGraph.empty()
                : ImportGraph.from(legacyImports, legacyReverseImports);

        TypeHierarchyGraph typeHierarchyGraph = (legacySupertypes.isEmpty() && legacySubtypes.isEmpty())
                ? TypeHierarchyGraph.empty()
                : TypeHierarchyGraph.from(legacySupertypes, legacySubtypes);

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
                importGraph,
                typeHierarchyGraph,
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

    /**
     * Hook to migrate DTOs between schema versions. Currently a no-op but structured for future migrations.
     */
    private static AnalyzerStateDto migrate(AnalyzerStateDto dto, SemVer from, SemVer to) {
        // No migration required yet. Future migrations can inspect `from` and `to` and transform the DTO.
        return dto;
    }
}
