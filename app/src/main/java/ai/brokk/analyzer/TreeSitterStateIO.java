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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
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
    static final SemVer CURRENT_SCHEMA = SemVer.parse("2.0.0");

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
            @Nullable String schemaVersion,
            @Nullable String languageInternalName) {

        @JsonCreator
        public AnalyzerStateDto(
                @JsonProperty("symbolIndex") Map<String, List<CodeUnitDto>> symbolIndex,
                @JsonProperty("codeUnitState") List<CodeUnitEntryDto> codeUnitState,
                @JsonProperty("fileState") List<FileStateEntryDto> fileState,
                @JsonProperty("symbolKeys") List<String> symbolKeys,
                @JsonProperty("snapshotEpochNanos") long snapshotEpochNanos,
                @JsonProperty("schemaVersion") @Nullable String schemaVersion,
                @JsonProperty("languageInternalName") @Nullable String languageInternalName) {
            this.symbolIndex = symbolIndex;
            this.codeUnitState = codeUnitState;
            this.fileState = fileState;
            this.symbolKeys = symbolKeys;
            this.snapshotEpochNanos = snapshotEpochNanos;
            this.schemaVersion = schemaVersion;
            this.languageInternalName = languageInternalName;
        }
    }

    /**
     * DTO for CodeUnitProperties that can be easily serialized.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeUnitPropertiesDto(
            @Nullable List<CodeUnitDto> children,
            @Nullable List<String> signatures,
            @Nullable List<IAnalyzer.Range> ranges,
            boolean hasBody) {}

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

    @Blocking
    public static void save(TreeSitterAnalyzer.AnalyzerState state, Path file) {
        save(state, file, null);
    }

    @Blocking
    public static void save(TreeSitterAnalyzer.AnalyzerState state, Path file, @Nullable Language language) {
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

            var dto = toDto(state, language);
            try (var os = Files.newOutputStream(temp);
                    var out = new LZ4FrameOutputStream(os)) {
                SMILE_MAPPER.writeValue(out, dto);
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
     * Load an AnalyzerState from the provided file in Smile format.
     *
     * <p>The language is resolved primarily from the DTO's languageInternalName, falling back to
     * filename inference (e.g., "java.bin.lz4" -> JAVA) if missing.
     *
     * <p>Version semantics:
     * <ul>
     *   <li>If major versions differ -> incompatible: return Optional.empty().
     *   <li>For strict languages (JAVA, TYPESCRIPT): if snapshot < CURRENT_SCHEMA, return empty.
     *   <li>For other languages: allow minor/patch differences and migrate.
     * </ul>
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

        // Determine language: DTO first, then filename fallback.
        Language language = null;
        if (dto.languageInternalName() != null) {
            try {
                language = Languages.valueOf(dto.languageInternalName());
            } catch (IllegalArgumentException e) {
                log.debug("Unknown languageInternalName '{}' in DTO at {}", dto.languageInternalName(), file);
            }
        }

        if (language == null) {
            String fileName = file.getFileName().toString();
            String suffix = ".bin.lz4";
            if (fileName.endsWith(suffix)) {
                String prefix = fileName.substring(0, fileName.length() - suffix.length());
                try {
                    language = Languages.valueOf(prefix.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    log.info("Could not infer language from filename {} or DTO; forcing rebuild.", fileName);
                    return Optional.empty();
                }
            } else {
                log.info("Could not determine language for {}; forcing rebuild.", file);
                return Optional.empty();
            }
        }

        // Interpret schema version field (backwards-compatible)
        SemVer fromVer = null;
        if (dto.schemaVersion() != null) {
            fromVer = SemVer.parse(dto.schemaVersion());
        }

        // If major versions differ, snapshot is incompatible
        if (fromVer != null && fromVer.major() != CURRENT_SCHEMA.major()) {
            log.info(
                    "Analyzer snapshot at {} has incompatible schema version {} (current {}). Ignoring snapshot and will rebuild.",
                    file,
                    fromVer,
                    CURRENT_SCHEMA);
            return Optional.empty();
        }

        // Check if language-specific rules require a rebuild (e.g. Java/TS fqName changes)
        if (TreeSitterAnalyzerStateMigrator.shouldForceRebuild(language, fromVer, CURRENT_SCHEMA)) {
            log.info(
                    "Analyzer snapshot at {} for {} has schema version {} which requires a rebuild.",
                    file,
                    language != null ? language.name() : "unknown",
                    fromVer != null ? fromVer : "legacy");
            return Optional.empty();
        }

        // Default: use current schema if missing for non-strict languages
        SemVer effectiveFromVer = fromVer != null ? fromVer : CURRENT_SCHEMA;

        // Allow minor/patch differences for now; delegate to migrator
        var migratedDto = TreeSitterAnalyzerStateMigrator.migrate(dto, effectiveFromVer, CURRENT_SCHEMA);

        var state = fromDto(migratedDto);
        long durMs = System.currentTimeMillis() - startMs;
        log.debug("Loaded TreeSitter AnalyzerState from {} in {} ms (schema {})", file, durMs, effectiveFromVer);
        return Optional.of(state);
    }

    /* ================= Converters ================= */

    /**
     * Convert live AnalyzerState to a serializable DTO.
     */
    public static AnalyzerStateDto toDto(TreeSitterAnalyzer.AnalyzerState state) {
        return toDto(state, null);
    }

    /**
     * Convert live AnalyzerState to a serializable DTO, including language identity.
     */
    public static AnalyzerStateDto toDto(TreeSitterAnalyzer.AnalyzerState state, @Nullable Language language) {
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

            var propsDto = new CodeUnitPropertiesDto(
                    childrenDtos,
                    new ArrayList<>(props.signatures()),
                    new ArrayList<>(props.ranges()),
                    props.hasBody());

            cuEntries.add(new CodeUnitEntryDto(toDto(e.getKey()), propsDto));
        }

        // fileState -> entries list (omit parsed tree)
        List<FileStateEntryDto> fileEntries = new ArrayList<>(state.fileState().size());
        for (var e : state.fileState().entrySet()) {
            var fileProps = e.getValue();

            var topLevelDtos = fileProps.topLevelCodeUnits().stream()
                    .map(TreeSitterStateIO::toDto)
                    .toList();

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
                CURRENT_SCHEMA.toString(),
                language != null ? language.internalName() : null);
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

            var children = v.children() == null
                    ? Collections.<CodeUnit>emptySet()
                    : v.children().stream()
                            .map(TreeSitterStateIO::fromDto)
                            .collect(Collectors.toCollection(LinkedHashSet::new));

            var signatures =
                    v.signatures() == null ? Collections.<String>emptySet() : new LinkedHashSet<>(v.signatures());

            var ranges = v.ranges() == null ? Collections.<IAnalyzer.Range>emptySet() : new LinkedHashSet<>(v.ranges());

            var props = new TreeSitterAnalyzer.CodeUnitProperties(
                    Collections.unmodifiableSet(new LinkedHashSet<>(children)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(signatures)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(ranges)),
                    v.hasBody());

            cuState.put(fromDto(entry.key()), props);
        }
        PMap<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties> codeUnitState = HashTreePMap.from(cuState);

        // Rebuild fileState PMap
        Map<ProjectFile, TreeSitterAnalyzer.FileProperties> fileStateMap = new HashMap<>();
        for (var entry : dto.fileState()) {
            var v = entry.value();

            var topLevel = v.topLevelCodeUnits().stream()
                    .map(TreeSitterStateIO::fromDto)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            var imports = v.importStatements().stream()
                    .map(TreeSitterStateIO::fromDto)
                    .toList();

            var fp = new TreeSitterAnalyzer.FileProperties(
                    Collections.unmodifiableSet(new LinkedHashSet<>(topLevel)), imports, v.containsTests());
            fileStateMap.put(fromDto(entry.key()), fp);
        }
        PMap<ProjectFile, TreeSitterAnalyzer.FileProperties> fileState = HashTreePMap.from(fileStateMap);

        // Rebuild SymbolKeyIndex
        var keySet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        keySet.addAll(dto.symbolKeys());
        var unmodifiableKeys = Collections.unmodifiableNavigableSet(keySet);
        var symbolKeyIndex = new TreeSitterAnalyzer.SymbolKeyIndex(unmodifiableKeys);

        // Construct new immutable AnalyzerState
        return new TreeSitterAnalyzer.AnalyzerState(
                symbolIndex, codeUnitState, fileState, symbolKeyIndex, dto.snapshotEpochNanos());
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
