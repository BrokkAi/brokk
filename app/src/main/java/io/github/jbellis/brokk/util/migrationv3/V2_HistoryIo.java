package io.github.jbellis.brokk.util.migrationv3;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.util.migrationv3.V2_FragmentDtos.AllFragmentsDto;
import io.github.jbellis.brokk.util.migrationv3.V2_FragmentDtos.CompactContextDto;
import io.github.jbellis.brokk.util.migrationv3.V2_FragmentDtos.ReferencedFragmentDto;
import io.github.jbellis.brokk.util.migrationv3.V2_FragmentDtos.TaskFragmentDto;
import io.github.jbellis.brokk.util.migrationv3.V2_FragmentDtos.VirtualFragmentDto;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class V2_HistoryIo {
    private static final Logger logger = LogManager.getLogger(V2_HistoryIo.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.CLOSE_CLOSEABLE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String V1_CONTEXTS_FILENAME = "contexts.jsonl";
    private static final String V1_FRAGMENTS_FILENAME = "fragments-v1.json";
    private static final String RESET_EDGES_FILENAME = "reset_edges.json";
    private static final String GIT_STATES_FILENAME = "git_states.json";
    private static final String ENTRY_INFOS_FILENAME = "entry_infos.json";
    private static final String IMAGES_DIR_PREFIX = "images/";
    /* legacy format (no UUID / resetEdges) */
    private static final int V1_FORMAT_VERSION = 1;
    /* current format */
    private static final int CURRENT_FORMAT_VERSION = 2;

    private V2_HistoryIo() {}

    public static @Nullable ContextHistory readZip(Path zip, IContextManager mgr) throws IOException {
        if (!Files.exists(zip)) {
            logger.warn("History zip file not found: {}. Returning empty history.", zip);
            return null;
        }

        // Peek into the zip to determine V1 format
        boolean hasV1FragmentsFile = false;
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V1_FRAGMENTS_FILENAME)) {
                    hasV1FragmentsFile = true;
                    break;
                }
            }
        } catch (IOException e) {
            logger.error("Error peeking into zip file {}: {}", zip, e.getMessage());
            // Treat as if V1 format not detected, will fall through to empty history
        }

        if (hasV1FragmentsFile) {
            logger.debug("Reading V1 format history from {}", zip);
            // ContextFragment.nextId is updated by V2_DtoMapper and ContextFragment constructors
            // during the deserialization process within readHistoryV1.
            return readHistoryV1(zip, mgr);
        } else {
            logger.warn(
                    "History zip file {} does not contain V1 history marker ({}). Returning empty history.",
                    zip,
                    V1_FRAGMENTS_FILENAME);
            return null;
        }
    }

    private static @Nullable ContextHistory readHistoryV1(Path zip, IContextManager mgr) throws IOException {
        AllFragmentsDto allFragmentsDto = null;
        List<String> compactContextDtoLines = new ArrayList<>();
        Map<String, byte[]> imageBytesMap = new HashMap<>();
        List<ContextHistory.ResetEdge> resetEdges = new ArrayList<>();
        Map<String, V2_DtoMapper.GitStateDto> gitStateDtos = new HashMap<>();
        Map<String, ContextHistory.ContextHistoryEntryInfo> entryInfoDtos = new HashMap<>();

        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V1_FRAGMENTS_FILENAME)) {
                    byte[] fragmentJsonBytes = zis.readAllBytes();
                    var fragmentJsonString = new String(fragmentJsonBytes, StandardCharsets.UTF_8)
                            .replace(
                                    "\"type\":\"io.github.jbellis.brokk.context.FragmentDtos",
                                    "\"type\":\"io.github.jbellis.brokk.util.migrationv3.V2_FragmentDtos")
                            .replace(
                                    "\"@class\":\"io.github.jbellis.brokk.context.FragmentDtos",
                                    "\"@class\":\"io.github.jbellis.brokk.util.migrationv3.V2_FragmentDtos")
                            .replace("\"summaryType\":\"CLASS_SKELETON\"", "\"summaryType\" : \"CODEUNIT_SKELETON\"");
                    allFragmentsDto = objectMapper.readValue(fragmentJsonString, AllFragmentsDto.class);
                    // zis.closeEntry(); // Removed as per original V1 read logic structure
                    if (allFragmentsDto.version() != V1_FORMAT_VERSION
                            && allFragmentsDto.version() != CURRENT_FORMAT_VERSION) {
                        logger.error(
                                "Unsupported fragments version: {}. Expected 1 or 2. Cannot load history.",
                                allFragmentsDto.version());
                        return null;
                    }
                } else if (entry.getName().equals(V1_CONTEXTS_FILENAME)) {
                    var reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            compactContextDtoLines.add(line);
                        }
                    }
                } else if (entry.getName().equals(RESET_EDGES_FILENAME)) {
                    byte[] edgeBytes = zis.readAllBytes();
                    record EdgeDto(String sourceId, String targetId) {}
                    List<EdgeDto> list = List.of(objectMapper.readValue(edgeBytes, EdgeDto[].class));
                    list.forEach(d -> resetEdges.add(new ContextHistory.ResetEdge(
                            UUID.fromString(d.sourceId()), UUID.fromString(d.targetId()))));
                } else if (entry.getName().equals(GIT_STATES_FILENAME)) {
                    byte[] gitStatesBytes = zis.readAllBytes();
                    var mapType = objectMapper
                            .getTypeFactory()
                            .constructMapType(HashMap.class, String.class, V2_DtoMapper.GitStateDto.class);
                    gitStateDtos = objectMapper.readValue(gitStatesBytes, mapType);
                } else if (entry.getName().equals(ENTRY_INFOS_FILENAME)) {
                    byte[] bytes = zis.readAllBytes();
                    var mapType = objectMapper
                            .getTypeFactory()
                            .constructMapType(
                                    HashMap.class, String.class, ContextHistory.ContextHistoryEntryInfo.class);
                    entryInfoDtos = objectMapper.readValue(bytes, mapType);
                } else if (entry.getName().startsWith(IMAGES_DIR_PREFIX) && !entry.isDirectory()) {
                    String fragmentIdHash = idFromNameV1(entry.getName());
                    imageBytesMap.put(fragmentIdHash, zis.readAllBytes());
                }
            }
        }

        if (allFragmentsDto == null) {
            logger.error("V1 history file {} is missing {}. Cannot load history.", zip, V1_FRAGMENTS_FILENAME);
            return null;
        }
        // No warning if compactContextDtoLines is empty but fragments exist, it's a valid state (empty history).

        Map<String, ContextFragment> fragmentCache = new ConcurrentHashMap<>(); // Changed to ConcurrentHashMap
        final Map<String, ReferencedFragmentDto> referencedDtosById = allFragmentsDto.referenced();
        final Map<String, VirtualFragmentDto> virtualDtosById = allFragmentsDto.virtual();
        final Map<String, TaskFragmentDto> taskDtosById = allFragmentsDto.task();

        // Populate cache by iterating over the keys of the DTO maps.
        // This ensures that computeIfAbsent is called for each known fragment ID once.
        // V2_DtoMapper.resolveAndBuildFragment will handle recursive resolution of dependencies.
        Stream.concat(
                        Stream.concat(referencedDtosById.keySet().stream(), virtualDtosById.keySet().stream()),
                        taskDtosById.keySet().stream())
                .distinct()
                .forEach(id -> fragmentCache.computeIfAbsent(
                        id,
                        currentId -> V2_DtoMapper.resolveAndBuildFragment(
                                currentId,
                                referencedDtosById,
                                virtualDtosById,
                                taskDtosById,
                                mgr,
                                imageBytesMap,
                                fragmentCache) // fragmentCache passed for recursive calls
                        ));

        var contexts = new ArrayList<Context>();
        for (String line : compactContextDtoLines) {
            try {
                CompactContextDto compactDto = objectMapper.readValue(line, CompactContextDto.class);
                // Fragments are already resolved and in fragmentCache.
                // V2_DtoMapper.fromCompactDto will retrieve them.
                contexts.add(V2_DtoMapper.fromCompactDto(compactDto, mgr, fragmentCache));
            } catch (Exception e) {
                logger.error("Failed to parse V1 CompactContextDto from line: {}", line, e);
                throw new IOException("Failed to parse V1 CompactContextDto from line: " + line, e);
            }
        }

        if (contexts.isEmpty()) {
            return null;
        }

        var gitStates = new HashMap<UUID, ContextHistory.GitState>();
        for (var entry : gitStateDtos.entrySet()) {
            var contextId = UUID.fromString(entry.getKey());
            var dto = entry.getValue();
            gitStates.put(contextId, new ContextHistory.GitState(dto.commitHash(), dto.diff()));
        }

        var entryInfos = new HashMap<UUID, ContextHistory.ContextHistoryEntryInfo>();
        for (var entry : entryInfoDtos.entrySet()) {
            var contextId = UUID.fromString(entry.getKey());
            entryInfos.put(contextId, entry.getValue());
        }

        return new ContextHistory(contexts, resetEdges, gitStates, entryInfos);
    }

    // Renamed from idFromName to idFromNameV1 to differentiate if V0 used numeric parsing
    private static String idFromNameV1(String entryName) {
        // entryName is like "images/hash_string.png"
        String nameWithoutPrefix = entryName.substring(IMAGES_DIR_PREFIX.length());
        int dotIndex = nameWithoutPrefix.lastIndexOf('.');
        return (dotIndex > 0) ? nameWithoutPrefix.substring(0, dotIndex) : nameWithoutPrefix;
    }
}
