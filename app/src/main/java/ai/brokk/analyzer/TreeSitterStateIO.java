package ai.brokk.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistence helper for TreeSitterAnalyzer.AnalyzerState using Jackson CBOR.
 *
 * Serializes AnalyzerState into DTOs:
 * - PMap fields are represented as standard Maps or entry lists,
 * - SymbolKeyIndex becomes List<String> (keys),
 * - FileProperties omits the parsed TSTree.
 */
public final class TreeSitterStateIO {
    private static final Logger log = LoggerFactory.getLogger(TreeSitterStateIO.class);

    // Dedicated CBOR ObjectMapper
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    private TreeSitterStateIO() {}

    /* ================= DTOs ================= */

    /**
     * DTO for AnalyzerState with only serializable components.
     */
    public static record AnalyzerStateDto(
            Map<String, List<CodeUnit>> symbolIndex,
            List<CodeUnitEntryDto> codeUnitState,
            List<FileStateEntryDto> fileState,
            List<String> symbolKeys,
            long snapshotEpochNanos) {}

    /**
     * DTO entry for CodeUnit -> CodeUnitProperties maps.
     */
    public static record CodeUnitEntryDto(
            CodeUnit key, TreeSitterAnalyzer.CodeUnitProperties value) {}

    /**
     * DTO entry for ProjectFile -> FileProperties maps.
     * FilePropertiesDto omits the parsed tree.
     */
    public static record FileStateEntryDto(ProjectFile key, FilePropertiesDto value) {}

    /**
     * DTO for TreeSitterAnalyzer.FileProperties without the TSTree.
     */
    public static record FilePropertiesDto(
            List<CodeUnit> topLevelCodeUnits,
            List<String> importStatements,
            Set<CodeUnit> resolvedImports) {}

    /* ================= Public API ================= */

    /**
     * Save the given AnalyzerState to the provided file in CBOR format.
     * Creates parent directories if necessary. On error, logs and returns.
     */
    public static void save(TreeSitterAnalyzer.AnalyzerState state, Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var dto = toDto(state);
            CBOR_MAPPER.writeValue(file.toFile(), dto);
            log.debug("Saved TreeSitter AnalyzerState to {}", file);
        } catch (IOException e) {
            log.warn("Failed to save TreeSitter AnalyzerState to {}: {}", file, e.getMessage(), e);
        }
    }

    /**
     * Load an AnalyzerState from the provided file in CBOR format.
     * Returns Optional.empty() if file is missing or deserialization fails.
     */
    public static Optional<TreeSitterAnalyzer.AnalyzerState> load(Path file) {
        if (!Files.exists(file)) {
            log.debug("Analyzer state file does not exist: {}", file);
            return Optional.empty();
        }
        try {
            var dto = CBOR_MAPPER.readValue(file.toFile(), AnalyzerStateDto.class);
            var state = fromDto(dto);
            log.debug("Loaded TreeSitter AnalyzerState from {}", file);
            return Optional.of(state);
        } catch (MismatchedInputException mie) {
            // Schema mismatch / incompatible version - trigger a rebuild
            log.warn("Analyzer state at {} appears incompatible ({}). Will rebuild analyzer.", file, mie.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Failed to load TreeSitter AnalyzerState from {}: {}", file, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /* ================= Converters ================= */

    /**
     * Convert live AnalyzerState to a serializable DTO.
     */
    public static AnalyzerStateDto toDto(TreeSitterAnalyzer.AnalyzerState state) {
        // symbolIndex is already Map<String, List<CodeUnit>> (PMap implements Map)
        Map<String, List<CodeUnit>> symbolIndexCopy = new HashMap<>(state.symbolIndex());

        // codeUnitState -> entries list
        List<CodeUnitEntryDto> cuEntries = new ArrayList<>(state.codeUnitState().size());
        for (var e : state.codeUnitState().entrySet()) {
            cuEntries.add(new CodeUnitEntryDto(e.getKey(), e.getValue()));
        }

        // fileState -> entries list (omit parsed tree)
        List<FileStateEntryDto> fileEntries = new ArrayList<>(state.fileState().size());
        for (var e : state.fileState().entrySet()) {
            var fileProps = e.getValue();
            var fpDto = new FilePropertiesDto(
                    fileProps.topLevelCodeUnits(),
                    fileProps.importStatements(),
                    fileProps.resolvedImports());
            fileEntries.add(new FileStateEntryDto(e.getKey(), fpDto));
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
                symbolKeys,
                state.snapshotEpochNanos());
    }

    /**
     * Convert DTO back into an immutable AnalyzerState snapshot.
     */
    public static TreeSitterAnalyzer.AnalyzerState fromDto(AnalyzerStateDto dto) {
        // Rebuild symbol index PMap
        PMap<String, List<CodeUnit>> symbolIndex = HashTreePMap.from(dto.symbolIndex());

        // Rebuild codeUnitState PMap
        Map<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties> cuState = new HashMap<>();
        for (var entry : dto.codeUnitState()) {
            cuState.put(entry.key(), entry.value());
        }
        PMap<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties> codeUnitState = HashTreePMap.from(cuState);

        // Rebuild fileState PMap (TSTree omitted => null)
        Map<ProjectFile, TreeSitterAnalyzer.FileProperties> fileStateMap = new HashMap<>();
        for (var entry : dto.fileState()) {
            var v = entry.value();
            var fp = new TreeSitterAnalyzer.FileProperties(
                    v.topLevelCodeUnits(),
                    null, // parsedTree intentionally omitted
                    v.importStatements(),
                    v.resolvedImports());
            fileStateMap.put(entry.key(), fp);
        }
        PMap<ProjectFile, TreeSitterAnalyzer.FileProperties> fileState = HashTreePMap.from(fileStateMap);

        // Rebuild SymbolKeyIndex
        var keySet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        keySet.addAll(dto.symbolKeys());
        var unmodifiableKeys = Collections.unmodifiableNavigableSet(keySet);
        var symbolKeyIndex = new TreeSitterAnalyzer.SymbolKeyIndex(unmodifiableKeys);

        // Construct new immutable AnalyzerState
        return new TreeSitterAnalyzer.AnalyzerState(
                symbolIndex, codeUnitState, fileState, symbolKeyIndex, dto.snapshotEpochNanos());
    }
}
