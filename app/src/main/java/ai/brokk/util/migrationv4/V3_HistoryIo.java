package ai.brokk.util.migrationv4;

import ai.brokk.IContextManager;
import ai.brokk.context.ContentDtos;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.HistoryIo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class V3_HistoryIo {
    private static final Logger logger = LogManager.getLogger(V3_HistoryIo.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.CLOSE_CLOSEABLE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Map legacy V3 fqcn-based polymorphic ids to our V3_FragmentDtos DTOs for migration reading
            .addHandler(new LegacyTypeMappingHandler(Map.of(
                    "io.github.jbellis.brokk.context.FragmentDtos", "ai.brokk.util.migrationv4.V3_FragmentDtos",
                    "ai.brokk.context.FragmentDtos", "ai.brokk.util.migrationv4.V3_FragmentDtos")));

    private static final String V3_FRAGMENTS_FILENAME = "fragments-v3.json";
    private static final String CONTEXTS_FILENAME = "contexts.jsonl";
    private static final String CONTENT_FILENAME = "content_metadata.json";
    private static final String CONTENT_DIR_PREFIX = "content/";
    private static final String RESET_EDGES_FILENAME = "reset_edges.json";
    private static final String GIT_STATES_FILENAME = "git_states.json";
    private static final String ENTRY_INFOS_FILENAME = "entry_infos.json";
    private static final String IMAGES_DIR_PREFIX = "images/";

    //    private static final int CURRENT_FORMAT_VERSION = 3;

    private V3_HistoryIo() {}

    public static ContextHistory readZip(Path zip, IContextManager mgr) throws IOException {
        if (!Files.exists(zip)) {
            throw new FileNotFoundException(zip.toString());
        }

        boolean isV3 = false;
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V3_FRAGMENTS_FILENAME)) isV3 = true;
            }
        }

        if (isV3) {
            return readZipV3(zip, mgr);
        }
        throw new InvalidObjectException("History zip file {} is not in a recognized format");
    }

    private static ContextHistory readZipV3(Path zip, IContextManager mgr) throws IOException {
        V3_FragmentDtos.AllFragmentsDto allFragmentsDto = null;
        var compactContextDtoLines = new ArrayList<String>();
        var imageBytesMap = new HashMap<String, byte[]>();
        var resetEdges = new ArrayList<ContextHistory.ResetEdge>();
        Map<String, ContextHistory.GitState> gitStateDtos = new HashMap<>();
        Map<String, V3_DtoMapper.GitStateDto> rawGitStateDtos = null;
        Map<String, ContextHistory.ContextHistoryEntryInfo> entryInfoDtos = new HashMap<>();
        Map<String, ContentDtos.ContentMetadataDto> contentMetadata = Map.of();
        var contentBytesMap = new HashMap<String, byte[]>();

        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                switch (entry.getName()) {
                    case V3_FRAGMENTS_FILENAME -> {
                        byte[] fragmentJsonBytes = remapPolymorphicTypeIds(zis.readAllBytes());
                        // Try parsing as-is; if type-id points at classes that exist on classpath but are not subtypes
                        // of our V3 DTOs (e.g., ai.brokk.context.FragmentDtos$*), remap type ids and retry.
                        try {
                            allFragmentsDto =
                                    objectMapper.readValue(fragmentJsonBytes, V3_FragmentDtos.AllFragmentsDto.class);
                        } catch (InvalidTypeIdException e) {
                            logger.debug(
                                    "Falling back to legacy type-id remapping for fragments-v3.json due to: {}",
                                    e.toString());
                            String remappedJson =
                                    remapLegacyTypeIds(new String(fragmentJsonBytes, StandardCharsets.UTF_8));
                            allFragmentsDto =
                                    objectMapper.readValue(remappedJson, V3_FragmentDtos.AllFragmentsDto.class);
                        }
                    }
                    case CONTENT_FILENAME -> {
                        var typeRef = new TypeReference<Map<String, ContentDtos.ContentMetadataDto>>() {};
                        contentMetadata = objectMapper.readValue(zis.readAllBytes(), typeRef);
                    }
                    case CONTEXTS_FILENAME -> {
                        var content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                        content.lines().filter(line -> !line.trim().isEmpty()).forEach(compactContextDtoLines::add);
                    }
                    case RESET_EDGES_FILENAME -> {
                        record EdgeDto(String sourceId, String targetId) {}
                        var list = List.of(objectMapper.readValue(zis.readAllBytes(), EdgeDto[].class));
                        list.forEach(d -> resetEdges.add(new ContextHistory.ResetEdge(
                                UUID.fromString(d.sourceId()), UUID.fromString(d.targetId()))));
                    }
                    case GIT_STATES_FILENAME -> {
                        var typeRef = new TypeReference<Map<String, V3_DtoMapper.GitStateDto>>() {};
                        rawGitStateDtos = objectMapper.readValue(zis.readAllBytes(), typeRef);
                    }
                    case ENTRY_INFOS_FILENAME -> {
                        byte[] bytes = zis.readAllBytes();
                        var typeRefNew = new TypeReference<Map<String, V3_FragmentDtos.EntryInfoDto>>() {};
                        Map<String, V3_FragmentDtos.EntryInfoDto> dtoMap = objectMapper.readValue(bytes, typeRefNew);
                        entryInfoDtos = V3_DtoMapper.fromEntryInfosDto(dtoMap, mgr);
                    }
                    default -> {
                        if (entry.getName().startsWith(IMAGES_DIR_PREFIX) && !entry.isDirectory()) {
                            String name = entry.getName().substring(IMAGES_DIR_PREFIX.length());
                            int dotIndex = name.lastIndexOf('.');
                            String fragmentIdHash = (dotIndex > 0) ? name.substring(0, dotIndex) : name;
                            imageBytesMap.put(fragmentIdHash, zis.readAllBytes());
                        } else if (entry.getName().startsWith(CONTENT_DIR_PREFIX) && !entry.isDirectory()) {
                            String contentId = entry.getName()
                                    .substring(CONTENT_DIR_PREFIX.length())
                                    .replaceFirst("\\.txt$", "");
                            contentBytesMap.put(contentId, zis.readAllBytes());
                        }
                    }
                }
            }
        }

        if (allFragmentsDto == null) {
            throw new InvalidObjectException("No fragments found");
        }

        var contentReader = new V3_HistoryIo.ContentReader(contentBytesMap);
        contentReader.setContentMetadata(contentMetadata);
        contentReader.setImageBytes(imageBytesMap);

        if (rawGitStateDtos != null) {
            for (var e : rawGitStateDtos.entrySet()) {
                var dto = e.getValue();
                String diff = null;
                if (dto.diffContentId() != null) {
                    diff = contentReader.readContent(dto.diffContentId());
                }
                gitStateDtos.put(e.getKey(), new ContextHistory.GitState(dto.commitHash(), diff));
            }
        }

        Map<String, ContextFragment> fragmentCache = new ConcurrentHashMap<>();
        final var referencedDtosById = allFragmentsDto.referenced();
        final var virtualDtosById = allFragmentsDto.virtual();
        final var taskDtosById = allFragmentsDto.task();

        Stream.concat(
                        Stream.concat(referencedDtosById.keySet().stream(), virtualDtosById.keySet().stream()),
                        taskDtosById.keySet().stream())
                .distinct()
                .forEach(id -> fragmentCache.computeIfAbsent(id, currentId -> {
                    try {
                        return V3_DtoMapper.resolveAndBuildFragment(
                                currentId,
                                referencedDtosById,
                                virtualDtosById,
                                taskDtosById,
                                mgr,
                                fragmentCache,
                                contentReader);
                    } catch (Exception e) {
                        logger.error("Error resolving and building fragment for ID {}: {}", currentId, e);
                        return null;
                    }
                }));

        var contexts = new ArrayList<Context>();
        for (String line : compactContextDtoLines) {
            V3_FragmentDtos.CompactContextDto compactDto =
                    objectMapper.readValue(line, V3_FragmentDtos.CompactContextDto.class);
            contexts.add(V3_DtoMapper.fromCompactDto(compactDto, mgr, fragmentCache, contentReader));
        }

        if (contexts.isEmpty()) {
            throw new InvalidObjectException("No contexts found");
        }

        var gitStates = new HashMap<UUID, ContextHistory.GitState>();
        gitStateDtos.forEach((key, value) -> gitStates.put(UUID.fromString(key), value));
        var entryInfos = new HashMap<UUID, ContextHistory.ContextHistoryEntryInfo>();
        entryInfoDtos.forEach((key, value) -> entryInfos.put(UUID.fromString(key), value));

        return new ContextHistory(contexts, resetEdges, gitStates, entryInfos);
    }

    // Remap legacy polymorphic type ids ("type" and "@class") from older namespaces to our V3 DTO namespace.
    private static byte[] remapPolymorphicTypeIds(byte[] jsonBytes) throws IOException {
        JsonNode root = objectMapper.readTree(jsonBytes);
        remapNodeInPlace(root);
        return objectMapper.writeValueAsBytes(root);
    }

    private static void remapNodeInPlace(JsonNode node) {
        if (node instanceof ObjectNode on) {
            var fields = on.fields();
            var toUpdate = new ArrayList<Map.Entry<String, String>>();
            while (fields.hasNext()) {
                var e = fields.next();
                var key = e.getKey();
                var value = e.getValue();
                if ((key.equals("type") || key.equals("@class")) && value.isTextual()) {
                    String remapped = remapClassName(value.asText());
                    if (!remapped.equals(value.asText())) {
                        toUpdate.add(Map.entry(key, remapped));
                    }
                } else {
                    remapNodeInPlace(value);
                }
            }
            // Apply updates after iteration to avoid concurrent modifications
            for (var e : toUpdate) {
                on.put(e.getKey(), e.getValue());
            }
        } else if (node instanceof ArrayNode an) {
            for (int i = 0, size = an.size(); i < size; i++) {
                remapNodeInPlace(an.get(i));
            }
        }
    }

    private static String remapClassName(String fqcn) {
        String[] legacyPrefixes = {"io.github.jbellis.brokk.context.FragmentDtos", "ai.brokk.context.FragmentDtos"};
        String targetPrefix = "ai.brokk.util.migrationv4.V3_FragmentDtos";
        for (String legacy : legacyPrefixes) {
            if (fqcn.startsWith(legacy)) {
                return targetPrefix + fqcn.substring(legacy.length());
            }
        }
        return fqcn;
    }

    public static class ContentReader {
        private final Map<String, byte[]> contentBytes;
        private Map<String, ContentDtos.ContentMetadataDto> contentMetadata = Map.of();
        private final Map<String, String> contentCache = new HashMap<>();
        private Map<String, byte[]> imageBytes = Map.of();

        public ContentReader(Map<String, byte[]> contentBytes) {
            this.contentBytes = contentBytes;
        }

        public void setContentMetadata(Map<String, ContentDtos.ContentMetadataDto> contentMetadata) {
            this.contentMetadata = contentMetadata;
        }

        public void setImageBytes(Map<String, byte[]> imageBytes) {
            this.imageBytes = imageBytes;
        }

        public byte @Nullable [] readImageBytes(String fragmentId) {
            return imageBytes.get(fragmentId);
        }

        public String readContent(String contentId) {
            var cached = contentCache.get(contentId);
            if (cached != null) {
                return cached;
            }

            var metadata = contentMetadata.get(contentId);
            if (metadata == null) throw new IllegalStateException("No metadata for content ID: " + contentId);

            byte[] bytes = contentBytes.get(contentId);
            if (bytes == null) {
                throw new IllegalStateException("Content not found for ID: " + contentId);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);

            String result;
            if (metadata instanceof ContentDtos.DiffContentMetadataDto d) {
                String baseContent = readContent(d.appliesTo());
                result = ContentDiffUtils.applyDiff(content, baseContent);
            } else {
                result = content;
            }

            contentCache.put(contentId, result);
            return result;
        }
    }

    /**
     * Remap legacy type-id prefixes embedded in fragments-v3.json to our migration V3 DTO namespace.
     * This handles cases where JSON was written with:
     *  - io.github.jbellis.brokk.context.FragmentDtos$*
     *  - ai.brokk.context.FragmentDtos$*
     *  - legacy runtime ContextFragment nested classes like ...ContextFragment$ProjectPathFragment
     */
    private static String remapLegacyTypeIds(String json) {
        String out = json;

        // 1) Direct DTO namespace remaps
        out = out.replace(
                "io.github.jbellis.brokk.context.FragmentDtos$", "ai.brokk.util.migrationv4.V3_FragmentDtos$");
        out = out.replace("ai.brokk.context.FragmentDtos$", "ai.brokk.util.migrationv4.V3_FragmentDtos$");

        // 2) Runtime ContextFragment nested classes -> corresponding DTOs
        //    Keep this in sync with LegacyTypeMappingHandler.RUNTIME_FRAGMENT_TO_DTO
        Map<String, String> runtimeToDto = getRuntimeToDto();

        String[] legacyContextFragmentPrefixes =
                new String[] {"io.github.jbellis.brokk.context.ContextFragment$", "ai.brokk.context.ContextFragment$"};

        for (String prefix : legacyContextFragmentPrefixes) {
            for (var e : runtimeToDto.entrySet()) {
                String legacy = prefix + e.getKey();
                String mapped = "ai.brokk.util.migrationv4.V3_FragmentDtos$" + e.getValue();
                out = out.replace(legacy, mapped);
            }
        }

        return out;
    }

    private static Map<String, String> getRuntimeToDto() {
        Map<String, String> runtimeToDto = new HashMap<>(Map.ofEntries(
                Map.entry("ProjectPathFragment", "ProjectFileDto"),
                Map.entry("ExternalPathFragment", "ExternalFileDto"),
                Map.entry("ImageFileFragment", "ImageFileDto"),
                Map.entry("GitFileFragment", "GitFileFragmentDto"),
                Map.entry("TaskFragment", "TaskFragmentDto"),
                Map.entry("StringFragment", "StringFragmentDto"),
                Map.entry("SearchFragment", "SearchFragmentDto"),
                Map.entry("StacktraceFragment", "StacktraceFragmentDto"),
                Map.entry("CallGraphFragment", "CallGraphFragmentDto"),
                Map.entry("CodeFragment", "CodeFragmentDto"),
                Map.entry("HistoryFragment", "HistoryFragmentDto"),
                Map.entry("PasteTextFragment", "PasteTextFragmentDto")));
        // Include additional mappings found in legacy runtime classes
        runtimeToDto.put("AnonymousImageFragment", "PasteImageFragmentDto");
        runtimeToDto.put("FrozenFragment", "FrozenFragmentDto");
        runtimeToDto.put("BuildFragment", "BuildFragmentDto");
        return runtimeToDto;
    }

    // Type-safe handler to map legacy FQCN-based polymorphic type ids to our V3 DTOs
    private static final class LegacyTypeMappingHandler
            extends com.fasterxml.jackson.databind.deser.DeserializationProblemHandler {
        private final Map<String, String> prefixMapping;

        // Map legacy runtime ContextFragment nested class names to V3 DTO nested class names
        private static final Map<String, String> RUNTIME_FRAGMENT_TO_DTO = Map.ofEntries(
                Map.entry("ProjectPathFragment", "ProjectFileDto"),
                Map.entry("ExternalPathFragment", "ExternalFileDto"),
                Map.entry("ImageFileFragment", "ImageFileDto"),
                Map.entry("GitFileFragment", "GitFileFragmentDto"),
                Map.entry("TaskFragment", "TaskFragmentDto"),
                Map.entry("StringFragment", "StringFragmentDto"),
                Map.entry("SearchFragment", "SearchFragmentDto"),
                Map.entry("StacktraceFragment", "StacktraceFragmentDto"),
                Map.entry("CallGraphFragment", "CallGraphFragmentDto"),
                Map.entry("CodeFragment", "CodeFragmentDto"),
                Map.entry("HistoryFragment", "HistoryFragmentDto"),
                Map.entry("PasteTextFragment", "PasteTextFragmentDto"),
                Map.entry("AnonymousImageFragment", "PasteImageFragmentDto"),
                Map.entry("FrozenFragment", "FrozenFragmentDto"),
                Map.entry("BuildFragment", "BuildFragmentDto"));

        LegacyTypeMappingHandler(Map<String, String> prefixMapping) {
            this.prefixMapping = prefixMapping;
        }

        @Override
        public @Nullable JavaType handleUnknownTypeId(
                DeserializationContext ctxt,
                JavaType baseType,
                String subTypeId,
                TypeIdResolver idResolver,
                String failureMsg) {
            // 1) Try existing prefix-based remap first
            var remapped = HistoryIo.LegacyTypeMappingHandler.getJavaTypeWithFallback(
                    ctxt, baseType, subTypeId, prefixMapping);
            if (remapped != null) {
                return remapped;
            }

            // 2) Handle legacy runtime class names like "...ContextFragment$X"
            int dollarIdx = subTypeId.lastIndexOf('$');
            if (dollarIdx > 0) {
                String outer = subTypeId.substring(0, dollarIdx);
                if (outer.endsWith(".ContextFragment")) {
                    String nested = subTypeId.substring(dollarIdx + 1);
                    String dtoSimple = RUNTIME_FRAGMENT_TO_DTO.get(nested);
                    if (dtoSimple != null) {
                        String mappedId = "ai.brokk.util.migrationv4.V3_FragmentDtos$" + dtoSimple;
                        try {
                            Class<?> target = Class.forName(mappedId);
                            if (logger.isDebugEnabled()) {
                                logger.debug(
                                        "Applying fallback mapping for legacy runtime type-id '{}' to DTO '{}'; baseType={}",
                                        subTypeId,
                                        mappedId,
                                        baseType);
                            }
                            return ctxt.getTypeFactory().constructSpecializedType(baseType, target);
                        } catch (ClassNotFoundException e) {
                            logger.warn(
                                    "Failed to load mapped V3 DTO class '{}' for legacy type-id '{}'",
                                    mappedId,
                                    subTypeId);
                            // fall through to return null below
                        }
                    }
                }
            }

            // No mapping found; let Jackson handle the failure
            return null;
        }
    }
}
