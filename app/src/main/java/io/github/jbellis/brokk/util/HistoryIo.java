package io.github.jbellis.brokk.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.*;
import io.github.jbellis.brokk.context.ContentDtos.ContentMetadataDto;
import io.github.jbellis.brokk.context.ContentDtos.DiffContentMetadataDto;
import io.github.jbellis.brokk.context.ContentDtos.FullContentMetadataDto;
import io.github.jbellis.brokk.context.FragmentDtos.*;
import io.github.jbellis.brokk.util.migrationv3.HistoryV3Migrator;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final String V1_FRAGMENTS_FILENAME = "fragments-v1.json";
    private static final String V3_FRAGMENTS_FILENAME = "fragments-v3.json";
    private static final String CONTEXTS_FILENAME = "contexts.jsonl";
    private static final String CONTENT_FILENAME = "content_metadata.json";
    private static final String CONTENT_DIR_PREFIX = "content/";
    private static final String RESET_EDGES_FILENAME = "reset_edges.json";
    private static final String GIT_STATES_FILENAME = "git_states.json";
    private static final String ENTRY_INFOS_FILENAME = "entry_infos.json";
    private static final String IMAGES_DIR_PREFIX = "images/";

    private static final int CURRENT_FORMAT_VERSION = 3;

    private HistoryIo() {}

    public static ContextHistory readZip(Path zip, IContextManager mgr) throws IOException {
        if (!Files.exists(zip)) {
            throw new FileNotFoundException(zip.toString());
        }

        boolean isV1 = false;
        boolean isV3 = false;
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V1_FRAGMENTS_FILENAME)) isV1 = true;
                if (entry.getName().equals(V3_FRAGMENTS_FILENAME)) isV3 = true;
            }
        }

        if (isV3) {
            return readZipV3(zip, mgr);
        } else if (isV1) {
            HistoryV3Migrator.migrate(zip, mgr);
            return readZipV3(zip, mgr);
        }
        throw new InvalidObjectException("History zip file {} is not in a recognized format");
    }

    private static ContextHistory readZipV3(Path zip, IContextManager mgr) throws IOException {
        AllFragmentsDto allFragmentsDto = null;
        var compactContextDtoLines = new ArrayList<String>();
        var imageBytesMap = new HashMap<String, byte[]>();
        var resetEdges = new ArrayList<ContextHistory.ResetEdge>();
        Map<String, ContextHistory.GitState> gitStateDtos = new HashMap<>();
        Map<String, DtoMapper.GitStateDto> rawGitStateDtos = null;
        Map<String, ContextHistory.ContextHistoryEntryInfo> entryInfoDtos = new HashMap<>();
        Map<String, ContentMetadataDto> contentMetadata = Map.of();
        var contentBytesMap = new HashMap<String, byte[]>();

        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                switch (entry.getName()) {
                    case V3_FRAGMENTS_FILENAME ->
                        allFragmentsDto = objectMapper.readValue(zis.readAllBytes(), AllFragmentsDto.class);
                    case CONTENT_FILENAME -> {
                        var typeRef = new TypeReference<Map<String, ContentMetadataDto>>() {};
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
                        var typeRef = new TypeReference<Map<String, DtoMapper.GitStateDto>>() {};
                        rawGitStateDtos = objectMapper.readValue(zis.readAllBytes(), typeRef);
                    }
                    case ENTRY_INFOS_FILENAME -> {
                        byte[] bytes = zis.readAllBytes();
                        var typeRefNew = new TypeReference<Map<String, EntryInfoDto>>() {};
                        Map<String, EntryInfoDto> dtoMap = objectMapper.readValue(bytes, typeRefNew);
                        entryInfoDtos = DtoMapper.fromEntryInfosDto(dtoMap);
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

        var contentReader = new ContentReader(contentBytesMap);
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
                .forEach(id -> fragmentCache.computeIfAbsent(
                        id,
                        currentId -> DtoMapper.resolveAndBuildFragment(
                                currentId,
                                referencedDtosById,
                                virtualDtosById,
                                taskDtosById,
                                mgr,
                                imageBytesMap,
                                fragmentCache,
                                contentReader)));

        var contexts = new ArrayList<Context>();
        for (String line : compactContextDtoLines) {
            CompactContextDto compactDto = objectMapper.readValue(line, CompactContextDto.class);
            contexts.add(DtoMapper.fromCompactDto(compactDto, mgr, fragmentCache, contentReader));
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

    private static String summarizeAction(Context ctx) {
        try {
            return ctx.action.get(Context.CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "(Summary Unavailable)";
        }
    }

    public static void writeZip(ContextHistory ch, Path target) throws IOException {
        var writer = new ContentWriter();
        var collectedReferencedDtos = new HashMap<String, ReferencedFragmentDto>();
        var collectedVirtualDtos = new HashMap<String, VirtualFragmentDto>();
        var collectedTaskDtos = new HashMap<String, TaskFragmentDto>();
        var imageDomainFragments = new HashSet<FrozenFragment>();
        var pastedImageFragments = new HashSet<ContextFragment.AnonymousImageFragment>();

        for (Context ctx : ch.getHistory()) {
            ctx.editableFiles().forEach(fragment -> {
                if (!collectedReferencedDtos.containsKey(fragment.id())) {
                    collectedReferencedDtos.put(fragment.id(), DtoMapper.toReferencedFragmentDto(fragment, writer));
                    if (fragment instanceof FrozenFragment ff && !ff.isText()) {
                        imageDomainFragments.add(ff);
                    }
                }
            });
            ctx.readonlyFiles().forEach(fragment -> {
                if (!collectedReferencedDtos.containsKey(fragment.id())) {
                    collectedReferencedDtos.put(fragment.id(), DtoMapper.toReferencedFragmentDto(fragment, writer));
                    if (fragment instanceof FrozenFragment ff && !ff.isText()) {
                        imageDomainFragments.add(ff);
                    }
                }
            });
            ctx.virtualFragments().forEach(vf -> {
                if (vf instanceof ContextFragment.TaskFragment taskFragment
                        && !(vf instanceof ContextFragment.SearchFragment)) {
                    if (!collectedTaskDtos.containsKey(taskFragment.id())) {
                        collectedTaskDtos.put(taskFragment.id(), DtoMapper.toTaskFragmentDto(taskFragment, writer));
                    }
                } else if (!collectedVirtualDtos.containsKey(vf.id())) {
                    collectedVirtualDtos.put(vf.id(), DtoMapper.toVirtualFragmentDto(vf, writer));
                    if (vf instanceof FrozenFragment ff && !ff.isText()) {
                        imageDomainFragments.add(ff);
                    }
                    if (vf instanceof ContextFragment.AnonymousImageFragment aif) {
                        pastedImageFragments.add(aif);
                    }
                    if (vf instanceof ContextFragment.HistoryFragment hf) {
                        hf.entries().stream()
                                .map(TaskEntry::log)
                                .filter(Objects::nonNull)
                                .forEach(log -> {
                                    if (!collectedTaskDtos.containsKey(log.id())) {
                                        collectedTaskDtos.put(log.id(), DtoMapper.toTaskFragmentDto(log, writer));
                                    }
                                });
                    }
                }
            });
            ctx.getTaskHistory().stream()
                    .map(TaskEntry::log)
                    .filter(Objects::nonNull)
                    .forEach(log -> {
                        if (!collectedTaskDtos.containsKey(log.id())) {
                            collectedTaskDtos.put(log.id(), DtoMapper.toTaskFragmentDto(log, writer));
                        }
                    });
            if (ctx.getParsedOutput() != null
                    && !collectedTaskDtos.containsKey(ctx.getParsedOutput().id())) {
                collectedTaskDtos.put(
                        ctx.getParsedOutput().id(), DtoMapper.toTaskFragmentDto(ctx.getParsedOutput(), writer));
            }
        }

        // Serialize all JSON content to byte arrays before writing to the zip stream
        var allFragmentsDto = new AllFragmentsDto(
                CURRENT_FORMAT_VERSION, collectedReferencedDtos, collectedVirtualDtos, collectedTaskDtos);
        byte[] fragmentsBytes = objectMapper.writeValueAsBytes(allFragmentsDto);

        var contextsJsonlContent = new StringBuilder();
        for (Context ctx : ch.getHistory()) {
            var taskEntryRefs = ctx.getTaskHistory().stream()
                    .map(te -> new TaskEntryRefDto(
                            te.sequence(),
                            te.log() != null ? te.log().id() : null,
                            te.summary() != null ? writer.writeContent(te.summary(), null) : null))
                    .toList();
            var compactDto = new CompactContextDto(
                    ctx.id().toString(),
                    ctx.editableFiles().map(ContextFragment::id).toList(),
                    ctx.readonlyFiles().map(ContextFragment::id).toList(),
                    ctx.virtualFragments().map(ContextFragment::id).toList(),
                    taskEntryRefs,
                    ctx.getParsedOutput() != null ? ctx.getParsedOutput().id() : null,
                    summarizeAction(ctx));
            contextsJsonlContent
                    .append(objectMapper.writeValueAsString(compactDto))
                    .append('\n');
        }
        byte[] contextsBytes = contextsJsonlContent.toString().getBytes(StandardCharsets.UTF_8);

        byte[] gitStatesBytes = null;
        var gitStates = ch.getGitStates();
        if (!gitStates.isEmpty()) {
            var gitStatesDto = new HashMap<String, DtoMapper.GitStateDto>();
            for (var entry : gitStates.entrySet()) {
                var gitState = entry.getValue();
                String diffContentId = null;
                if (gitState.diff() != null) {
                    diffContentId = writer.writeContent(gitState.diff(), null);
                }
                gitStatesDto.put(
                        entry.getKey().toString(), new DtoMapper.GitStateDto(gitState.commitHash(), diffContentId));
            }
            gitStatesBytes = objectMapper.writeValueAsBytes(gitStatesDto);
        }

        byte[] entryInfosBytes = null;
        var entryInfos = ch.getEntryInfos();
        if (!entryInfos.isEmpty()) {
            var entryInfosDto = DtoMapper.toEntryInfosDto(entryInfos);
            entryInfosBytes = objectMapper.writeValueAsBytes(entryInfosDto);
        }

        byte[] resetEdgesBytes = null;
        if (!ch.getResetEdges().isEmpty()) {
            record EdgeDto(String sourceId, String targetId) {}
            var dtos = ch.getResetEdges().stream()
                    .map(e -> new EdgeDto(e.sourceId().toString(), e.targetId().toString()))
                    .toList();
            resetEdgesBytes = objectMapper.writeValueAsBytes(dtos);
        }

        final var finalGitStatesBytes = gitStatesBytes;
        final var finalEntryInfosBytes = entryInfosBytes;
        final var finalResetEdgesBytes = resetEdgesBytes;
        AtomicWrites.atomicSave(target, out -> {
            try (var zos = new ZipOutputStream(out)) {
                zos.putNextEntry(new ZipEntry(V3_FRAGMENTS_FILENAME));
                zos.write(fragmentsBytes);
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry(CONTENT_FILENAME));
                var typeRef = new TypeReference<Map<String, ContentMetadataDto>>() {};
                byte[] contentMetadataBytes =
                        objectMapper.writerFor(typeRef).writeValueAsBytes(writer.getContentMetadata());
                zos.write(contentMetadataBytes);
                zos.closeEntry();

                for (var entry : writer.getContentBytes().entrySet()) {
                    zos.putNextEntry(new ZipEntry(CONTENT_DIR_PREFIX + entry.getKey() + ".txt"));
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }

                zos.putNextEntry(new ZipEntry(CONTEXTS_FILENAME));
                zos.write(contextsBytes);
                zos.closeEntry();

                if (finalGitStatesBytes != null) {
                    zos.putNextEntry(new ZipEntry(GIT_STATES_FILENAME));
                    zos.write(finalGitStatesBytes);
                    zos.closeEntry();
                }

                if (finalEntryInfosBytes != null) {
                    zos.putNextEntry(new ZipEntry(ENTRY_INFOS_FILENAME));
                    zos.write(finalEntryInfosBytes);
                    zos.closeEntry();
                }

                if (finalResetEdgesBytes != null) {
                    zos.putNextEntry(new ZipEntry(RESET_EDGES_FILENAME));
                    zos.write(finalResetEdgesBytes);
                    zos.closeEntry();
                }

                for (FrozenFragment ff : imageDomainFragments) {
                    byte[] imageBytes = ff.imageBytesContent();
                    if (imageBytes != null) {
                        ZipEntry entry = new ZipEntry(
                                IMAGES_DIR_PREFIX + ff.id() + ".png"); // Assumes PNG, consider content type if varied
                        entry.setMethod(ZipEntry.STORED); // For uncompressed images, or DEFLATED if compression desired
                        entry.setSize(imageBytes.length);
                        entry.setCompressedSize(imageBytes.length); // If STORED
                        var crc = new CRC32();
                        crc.update(imageBytes);
                        entry.setCrc(crc.getValue());
                        zos.putNextEntry(entry);
                        zos.write(imageBytes);
                        zos.closeEntry();
                    }
                }

                for (var aif : pastedImageFragments) {
                    try {
                        byte[] imageBytes = aif.imageBytes();
                        if (imageBytes == null) {
                            logger.warn("Skipping image fragment {} because imageBytes is null", aif.id());
                            continue;
                        }
                        ZipEntry entry = new ZipEntry(IMAGES_DIR_PREFIX + aif.id() + ".png"); // Assumes PNG
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(imageBytes.length);
                        entry.setCompressedSize(imageBytes.length); // If STORED
                        var crc = new CRC32();
                        crc.update(imageBytes);
                        entry.setCrc(crc.getValue());
                        zos.putNextEntry(entry);
                        zos.write(imageBytes);
                        zos.closeEntry();
                    } catch (IOException e) {
                        logger.warn("Could not write pasted image {} to history zip: {}", aif.id(), e.getMessage());
                    }
                }
            }
        });
    }

    public static class ContentWriter {
        private final Map<String, ContentMetadataDto> contentMetadata = new HashMap<>();
        private final Map<String, byte[]> contentBytes = new HashMap<>();
        private final Map<String, String> fileKeyToLastContentId = new HashMap<>();
        private final Map<String, String> fullContentToId = new HashMap<>();
        private final Map<String, String> idToFullContent = new HashMap<>();

        public String writeContent(String content, @Nullable String fileKey) {
            int revision = 1;
            if (fileKey != null) {
                var lastContentId = fileKeyToLastContentId.get(fileKey);
                if (lastContentId != null) {
                    var lastContent = idToFullContent.get(lastContentId);
                    var lastMetadata = contentMetadata.get(lastContentId);
                    if (lastContent != null && lastMetadata != null) {
                        if (content.equals(lastContent)) {
                            return lastContentId;
                        }
                        revision = lastMetadata.revision() + 1;
                        var diff = ContentDiffUtils.diff(lastContent, content);
                        var diffRatio = (double) diff.length() / content.length();
                        if (diffRatio < 0.75) {
                            // Make sure not to take diff bytes for UUID generation, because two diffs can be
                            // the same but apply to different contents
                            var contentId = UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8))
                                    .toString();
                            contentMetadata.put(contentId, new DiffContentMetadataDto(revision, lastContentId));
                            contentBytes.put(contentId, diff.getBytes(StandardCharsets.UTF_8));
                            fileKeyToLastContentId.put(fileKey, contentId);
                            idToFullContent.put(contentId, content);
                            return contentId;
                        }
                    }
                }
            }

            var existingId = fullContentToId.get(content);
            if (existingId != null) {
                if (fileKey != null) fileKeyToLastContentId.put(fileKey, existingId);
                return existingId;
            }

            byte[] fullContentBytes = content.getBytes(StandardCharsets.UTF_8);
            var contentId = UUID.nameUUIDFromBytes(fullContentBytes).toString();
            contentMetadata.put(contentId, new FullContentMetadataDto(revision));
            contentBytes.put(contentId, fullContentBytes);
            fullContentToId.put(content, contentId);
            if (fileKey != null) fileKeyToLastContentId.put(fileKey, contentId);
            idToFullContent.put(contentId, content);
            return contentId;
        }

        public Map<String, ContentMetadataDto> getContentMetadata() {
            return contentMetadata;
        }

        public Map<String, byte[]> getContentBytes() {
            return contentBytes;
        }
    }

    public static class ContentReader {
        private final Map<String, byte[]> contentBytes;
        private Map<String, ContentMetadataDto> contentMetadata = Map.of();
        private final Map<String, String> contentCache = new HashMap<>();

        public ContentReader(Map<String, byte[]> contentBytes) {
            this.contentBytes = contentBytes;
        }

        public void setContentMetadata(Map<String, ContentMetadataDto> contentMetadata) {
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
            if (metadata instanceof DiffContentMetadataDto d) {
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
