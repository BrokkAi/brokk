package io.github.jbellis.brokk.util.migrationv4;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.ContentDtos;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.util.ContentDiffUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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

public final class V3_HistoryIo {
    private static final Logger logger = LogManager.getLogger(V3_HistoryIo.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.CLOSE_CLOSEABLE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
                        byte[] fragmentJsonBytes = zis.readAllBytes();
                        // TODO: [Migration4] Expect more of this class replacement shenanigans
                        var fragmentJsonString = new String(fragmentJsonBytes, StandardCharsets.UTF_8)
                                .replace(
                                        "\"type\":\"io.github.jbellis.brokk.context.FragmentDtos",
                                        "\"type\":\"io.github.jbellis.brokk.util.migrationv4.V3_FragmentDtos")
                                .replace(
                                        "\"@class\":\"io.github.jbellis.brokk.context.FragmentDtos",
                                        "\"@class\":\"io.github.jbellis.brokk.util.migrationv4.V3_FragmentDtos")
                                .replace("\"summaryType\":\"CLASS_SKELETON\"", "\"summaryType\" : \"CODEUNIT_SKELETON\"");
                        allFragmentsDto = objectMapper.readValue(fragmentJsonString, V3_FragmentDtos.AllFragmentsDto.class);
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
                        entryInfoDtos = V3_DtoMapper.fromEntryInfosDto(dtoMap);
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
                                imageBytesMap,
                                fragmentCache,
                                contentReader);
                    } catch (Exception e) {
                        logger.error("Error resolving and building fragment for ID {}: {}", currentId, e);
                        return null;
                    }
                }));

        var contexts = new ArrayList<Context>();
        for (String line : compactContextDtoLines) {
            V3_FragmentDtos.CompactContextDto compactDto = objectMapper.readValue(line, V3_FragmentDtos.CompactContextDto.class);
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

    public static class ContentWriter {
        private final Map<String, ContentDtos.ContentMetadataDto> contentMetadata = new HashMap<>();
        private final Map<String, byte[]> contentBytes = new HashMap<>();
        private final Map<String, String> fileKeyToLastContentId = new HashMap<>();
        private final Map<String, String> idToFullContent = new HashMap<>();

        public String writeContent(String content, @Nullable String fileKey) {
            byte[] fullContentBytes = content.getBytes(StandardCharsets.UTF_8);
            var contentId = UUID.nameUUIDFromBytes(fullContentBytes).toString();

            if (idToFullContent.containsKey(contentId)) {
                if (fileKey != null) {
                    fileKeyToLastContentId.put(fileKey, contentId);
                }
                return contentId;
            }

            idToFullContent.put(contentId, content);

            int revision = 1;
            if (fileKey != null) {
                var lastContentId = fileKeyToLastContentId.get(fileKey);
                fileKeyToLastContentId.put(fileKey, contentId);
                if (lastContentId != null) {
                    var lastContent = idToFullContent.get(lastContentId);
                    var lastMetadata = contentMetadata.get(lastContentId);
                    if (lastContent != null && lastMetadata != null) {
                        revision = lastMetadata.revision() + 1;
                        var diff = ContentDiffUtils.diff(lastContent, content);
                        var diffRatio = (double) diff.length() / content.length();
                        if (diffRatio < 0.75) {
                            contentMetadata.put(contentId, new ContentDtos.DiffContentMetadataDto(revision, lastContentId));
                            contentBytes.put(contentId, diff.getBytes(StandardCharsets.UTF_8));
                            return contentId;
                        }
                    }
                }
            }

            contentMetadata.put(contentId, new ContentDtos.FullContentMetadataDto(revision));
            contentBytes.put(contentId, fullContentBytes);

            return contentId;
        }

        public Map<String, ContentDtos.ContentMetadataDto> getContentMetadata() {
            return contentMetadata;
        }

        public Map<String, byte[]> getContentBytes() {
            return contentBytes;
        }
    }

    public static class ContentReader {
        private final Map<String, byte[]> contentBytes;
        private Map<String, ContentDtos.ContentMetadataDto> contentMetadata = Map.of();
        private final Map<String, String> contentCache = new HashMap<>();

        public ContentReader(Map<String, byte[]> contentBytes) {
            this.contentBytes = contentBytes;
        }

        public void setContentMetadata(Map<String, ContentDtos.ContentMetadataDto> contentMetadata) {
            this.contentMetadata = contentMetadata;
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
}
