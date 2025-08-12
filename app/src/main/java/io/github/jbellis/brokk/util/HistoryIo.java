package io.github.jbellis.brokk.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.*;
import io.github.jbellis.brokk.context.FragmentDtos.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class HistoryIo {
    private static final Logger logger = LogManager.getLogger(HistoryIo.class);
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

    private HistoryIo() {}

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
            // ContextFragment.nextId is updated by DtoMapper and ContextFragment constructors
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
        java.util.List<String> compactContextDtoLines = new ArrayList<>();
        Map<String, byte[]> imageBytesMap = new HashMap<>();
        List<ContextHistory.ResetEdge> resetEdges = new java.util.ArrayList<>();
        Map<String, DtoMapper.GitStateDto> gitStateDtos = new HashMap<>();
        Map<String, ContextHistory.ContextHistoryEntryInfo> entryInfoDtos = new HashMap<>();

        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V1_FRAGMENTS_FILENAME)) {
                    byte[] fragmentJsonBytes = zis.readAllBytes();
                    allFragmentsDto = objectMapper.readValue(fragmentJsonBytes, AllFragmentsDto.class);
                    // zis.closeEntry(); // Removed as per original V1 read logic structure
                    if (allFragmentsDto.version() != V1_FORMAT_VERSION
                            && allFragmentsDto.version() != CURRENT_FORMAT_VERSION) {
                        logger.error(
                                "Unsupported fragments version: {}. Expected 1 or 2. Cannot load history.",
                                allFragmentsDto.version());
                        return null;
                    }
                } else if (entry.getName().equals(V1_CONTEXTS_FILENAME)) {
                    var reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(zis, java.nio.charset.StandardCharsets.UTF_8));
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
                            java.util.UUID.fromString(d.sourceId()), java.util.UUID.fromString(d.targetId()))));
                } else if (entry.getName().equals(GIT_STATES_FILENAME)) {
                    byte[] gitStatesBytes = zis.readAllBytes();
                    var mapType = objectMapper
                            .getTypeFactory()
                            .constructMapType(HashMap.class, String.class, DtoMapper.GitStateDto.class);
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
        // No warning if compactContextDtoLines is empty but fragments exist, it's a valid state (empty
        // history).

        Map<String, ContextFragment> fragmentCache =
                new java.util.concurrent.ConcurrentHashMap<>(); // Changed to ConcurrentHashMap
        final Map<String, ReferencedFragmentDto> referencedDtosById = allFragmentsDto.referenced();
        final Map<String, VirtualFragmentDto> virtualDtosById = allFragmentsDto.virtual();
        final Map<String, TaskFragmentDto> taskDtosById = allFragmentsDto.task();

        // Populate cache by iterating over the keys of the DTO maps.
        // This ensures that computeIfAbsent is called for each known fragment ID once.
        // DtoMapper.resolveAndBuildFragment will handle recursive resolution of dependencies.
        Stream.concat(
                        Stream.concat(referencedDtosById.keySet().stream(), virtualDtosById.keySet().stream()),
                        taskDtosById.keySet().stream())
                .distinct()
                .forEach(id -> fragmentCache.computeIfAbsent(
                        id,
                        currentId -> DtoMapper.resolveAndBuildFragment(
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
                // DtoMapper.fromCompactDto will retrieve them.
                contexts.add(DtoMapper.fromCompactDto(compactDto, mgr, fragmentCache));
            } catch (Exception e) {
                logger.error("Failed to parse V1 CompactContextDto from line: {}", line, e);
                throw new IOException("Failed to parse V1 CompactContextDto from line: " + line, e);
            }
        }

        if (contexts.isEmpty()) {
            return null;
        }

        var gitStates = new HashMap<java.util.UUID, ContextHistory.GitState>();
        for (var entry : gitStateDtos.entrySet()) {
            var contextId = java.util.UUID.fromString(entry.getKey());
            var dto = entry.getValue();
            gitStates.put(contextId, new ContextHistory.GitState(dto.commitHash(), dto.diff()));
        }

        var entryInfos = new HashMap<java.util.UUID, ContextHistory.ContextHistoryEntryInfo>();
        for (var entry : entryInfoDtos.entrySet()) {
            var contextId = java.util.UUID.fromString(entry.getKey());
            entryInfos.put(contextId, entry.getValue());
        }

        return new ContextHistory(contexts, resetEdges, gitStates, entryInfos);
    }

    private static String summarizeAction(Context ctx) {
        try {
            // Assuming ctx.action is Future<String>
            String summary = ctx.action.get(Context.CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return (summary == null || summary.isBlank()) ? "Context updated" : summary;
        } catch (java.util.concurrent.TimeoutException e) {
            return "(Summary Unavailable)";
        } catch (Exception e) {
            logger.warn("Error retrieving action summary: {}", e.getMessage());
            return "(Error retrieving summary)";
        }
    }

    // Renamed from idFromName to idFromNameV1 to differentiate if V0 used numeric parsing
    private static String idFromNameV1(String entryName) {
        // entryName is like "images/hash_string.png"
        String nameWithoutPrefix = entryName.substring(IMAGES_DIR_PREFIX.length());
        int dotIndex = nameWithoutPrefix.lastIndexOf('.');
        return (dotIndex > 0) ? nameWithoutPrefix.substring(0, dotIndex) : nameWithoutPrefix;
    }

    public static void writeZip(ContextHistory ch, Path target) throws IOException {
        Map<String, ReferencedFragmentDto> collectedReferencedDtos = new HashMap<>(); // Key is String
        Map<String, VirtualFragmentDto> collectedVirtualDtos = new HashMap<>(); // Key is String
        Map<String, TaskFragmentDto> collectedTaskDtos = new HashMap<>(); // Key is String
        Set<FrozenFragment> imageDomainFragments = new java.util.HashSet<>();

        // Collect all unique fragments and their DTOs. fragment.id() is String.
        for (Context ctx : ch.getHistory()) {
            ctx.editableFiles().forEach(fragment -> {
                if (!collectedReferencedDtos.containsKey(fragment.id())) {
                    collectedReferencedDtos.put(
                            fragment.id(), (ReferencedFragmentDto) DtoMapper.toReferencedFragmentDto(fragment));
                    if (fragment instanceof FrozenFragment ff && !ff.isText()) {
                        imageDomainFragments.add(ff);
                    }
                }
            });
            ctx.readonlyFiles().forEach(fragment -> {
                if (!collectedReferencedDtos.containsKey(fragment.id())) {
                    collectedReferencedDtos.put(
                            fragment.id(), (ReferencedFragmentDto) DtoMapper.toReferencedFragmentDto(fragment));
                    if (fragment instanceof FrozenFragment ff && !ff.isText()) {
                        imageDomainFragments.add(ff);
                    }
                }
            });
            ctx.virtualFragments().forEach(vf -> {
                String id = vf.id();
                if (vf instanceof ContextFragment.SearchFragment sf) {
                    // SearchFragmentDto is a VirtualFragmentDto, not a TaskFragmentDto in terms of
                    // map placement
                    if (!collectedVirtualDtos.containsKey(id)) {
                        collectedVirtualDtos.put(id, DtoMapper.toVirtualFragmentDto(sf));
                    }
                } else if (vf instanceof ContextFragment.HistoryFragment hf) {
                    if (!collectedVirtualDtos.containsKey(id)) {
                        collectedVirtualDtos.put(id, DtoMapper.toVirtualFragmentDto(hf));
                        // Recursively ensure TaskFragments within this HistoryFragment are collected
                        // into taskDtos
                        hf.entries().forEach(taskEntry -> {
                            if (taskEntry.log() != null
                                    && !collectedTaskDtos.containsKey(
                                            taskEntry.log().id())) {
                                collectedTaskDtos.put(
                                        taskEntry.log().id(), DtoMapper.toTaskFragmentDto(taskEntry.log()));
                            }
                        });
                    }
                } else if (vf instanceof ContextFragment.TaskFragment tf) {
                    // This handles plain TaskFragments (not SearchFragments, not nested in
                    // HistoryFragment directly here)
                    if (!collectedTaskDtos.containsKey(id)) {
                        collectedTaskDtos.put(id, DtoMapper.toTaskFragmentDto(tf));
                    }
                } else { // Other VirtualFragments (StringFragment, SkeletonFragment,
                    // FrozenFragment, etc.)
                    if (!collectedVirtualDtos.containsKey(id)) {
                        collectedVirtualDtos.put(id, DtoMapper.toVirtualFragmentDto(vf));
                        if (vf instanceof FrozenFragment ff && !ff.isText()) {
                            imageDomainFragments.add(ff);
                        }
                    }
                }
            });
            ctx.getTaskHistory()
                    .forEach(
                            te -> { // Handles TaskFragments directly in the context's task history
                                if (te.log() != null
                                        && !collectedTaskDtos.containsKey(
                                                te.log().id())) {
                                    collectedTaskDtos.put(te.log().id(), DtoMapper.toTaskFragmentDto(te.log()));
                                }
                            });
            if (ctx.getParsedOutput() != null
                    && !collectedTaskDtos.containsKey(ctx.getParsedOutput().id())) {
                collectedTaskDtos.put(ctx.getParsedOutput().id(), DtoMapper.toTaskFragmentDto(ctx.getParsedOutput()));
            }
        }

        try (var zos = new ZipOutputStream(Files.newOutputStream(target))) {
            var allFragmentsDto = new AllFragmentsDto(
                    CURRENT_FORMAT_VERSION, collectedReferencedDtos, collectedVirtualDtos, collectedTaskDtos);
            byte[] fragmentsJsonBytes = objectMapper.writeValueAsBytes(allFragmentsDto);
            ZipEntry fragmentsEntry = new ZipEntry(V1_FRAGMENTS_FILENAME);
            zos.putNextEntry(fragmentsEntry);
            zos.write(fragmentsJsonBytes);
            zos.closeEntry();

            var contextsJsonlContent = new StringBuilder();
            for (Context ctx : ch.getHistory()) {
                var editableIds = ctx.editableFiles().map(ContextFragment::id).toList();
                var readonlyIds = ctx.readonlyFiles().map(ContextFragment::id).toList();
                var virtualIds = ctx.virtualFragments()
                        .map(ContextFragment.VirtualFragment::id)
                        .toList();
                var taskEntryRefs = ctx.getTaskHistory().stream()
                        .map(te -> new TaskEntryRefDto(
                                te.sequence(), te.log() != null ? te.log().id() : null, te.summary()))
                        .toList();
                String parsedOutputId =
                        ctx.getParsedOutput() != null ? ctx.getParsedOutput().id() : null;

                var compactDto = new CompactContextDto(
                        ctx.id().toString(),
                        editableIds,
                        readonlyIds,
                        virtualIds,
                        taskEntryRefs,
                        parsedOutputId,
                        summarizeAction(ctx));
                contextsJsonlContent
                        .append(objectMapper.writeValueAsString(compactDto))
                        .append('\n');
            }
            byte[] contextsJsonlBytes =
                    contextsJsonlContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ZipEntry contextsEntry = new ZipEntry(V1_CONTEXTS_FILENAME);
            zos.putNextEntry(contextsEntry);
            zos.write(contextsJsonlBytes);
            zos.closeEntry();

            /* -------- GitStates (V2) -------- */
            var gitStates = ch.getGitStates();
            if (!gitStates.isEmpty()) {
                var gitStatesDto = new HashMap<String, DtoMapper.GitStateDto>();
                for (var entry : gitStates.entrySet()) {
                    var contextId = entry.getKey().toString();
                    var gitState = entry.getValue();
                    var dto = new DtoMapper.GitStateDto(gitState.commitHash(), gitState.diff());
                    gitStatesDto.put(contextId, dto);
                }
                var gitStatesJson = objectMapper.writeValueAsBytes(gitStatesDto);
                ZipEntry gitStatesEntry = new ZipEntry(GIT_STATES_FILENAME);
                zos.putNextEntry(gitStatesEntry);
                zos.write(gitStatesJson);
                zos.closeEntry();
            }

            /* -------- EntryInfos (V2) -------- */
            var entryInfos = ch.getEntryInfos();
            if (!entryInfos.isEmpty()) {
                var entryInfosToStringKeys = new HashMap<String, ContextHistory.ContextHistoryEntryInfo>();
                for (var entry : entryInfos.entrySet()) {
                    entryInfosToStringKeys.put(entry.getKey().toString(), entry.getValue());
                }
                var entryInfosJson = objectMapper.writeValueAsBytes(entryInfosToStringKeys);
                ZipEntry entryInfosEntry = new ZipEntry(ENTRY_INFOS_FILENAME);
                zos.putNextEntry(entryInfosEntry);
                zos.write(entryInfosJson);
                zos.closeEntry();
            }

            /* -------- ResetEdges (V2) -------- */
            if (!ch.getResetEdges().isEmpty()) {
                record EdgeDto(String sourceId, String targetId) {}
                var edgesJson = objectMapper.writeValueAsBytes(ch.getResetEdges().stream()
                        .map(e -> new EdgeDto(
                                e.sourceId().toString(), e.targetId().toString()))
                        .toList());
                ZipEntry edgeEntry = new ZipEntry(RESET_EDGES_FILENAME);
                zos.putNextEntry(edgeEntry);
                zos.write(edgesJson);
                zos.closeEntry();
            }

            for (FrozenFragment ff : imageDomainFragments) {
                byte[] imageBytes = ff.imageBytesContent();
                if (imageBytes != null && imageBytes.length > 0) {
                    try {
                        // ff.id() is the String hash
                        ZipEntry entry = new ZipEntry(
                                IMAGES_DIR_PREFIX + ff.id() + ".png"); // Assumes PNG, consider content type if varied
                        entry.setMethod(ZipEntry.STORED); // For uncompressed images, or DEFLATED if compression desired
                        entry.setSize(imageBytes.length);
                        entry.setCompressedSize(imageBytes.length); // If STORED
                        CRC32 crc = new CRC32();
                        crc.update(imageBytes);
                        entry.setCrc(crc.getValue());

                        zos.putNextEntry(entry);
                        zos.write(imageBytes);
                        zos.closeEntry();
                    } catch (IOException e) {
                        logger.error("Failed to write image for fragment {} to zip", ff.id(), e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to write history zip file (V1 format): {}", target, e);
            throw new IOException("Failed to write history zip file (V1 format): " + target, e);
        }
    }
}
