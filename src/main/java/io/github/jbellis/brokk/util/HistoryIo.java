package io.github.jbellis.brokk.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.*;
import io.github.jbellis.brokk.context.FragmentDtos.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class HistoryIo {
    private static final Logger logger = LogManager.getLogger(HistoryIo.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.CLOSE_CLOSEABLE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String V1_FRAGMENTS_FILENAME = "fragments-v1.json";
    private static final String V1_CONTEXTS_FILENAME = "contexts.jsonl";
    private static final String V0_HISTORY_FILENAME = "history.jsonl";
    private static final String IMAGES_DIR_PREFIX = "images/";
    private static final int V1_FORMAT_VERSION = 1;

    private HistoryIo() {}

    public static void writeZip(ContextHistory ch, Path target) throws IOException {
        Map<Integer, ReferencedFragmentDto> collectedReferencedDtos = new HashMap<>();
        Map<Integer, VirtualFragmentDto> collectedVirtualDtos = new HashMap<>();
        Map<Integer, TaskFragmentDto> collectedTaskDtos = new HashMap<>();
        Set<FrozenFragment> imageDomainFragments = new java.util.HashSet<>();

        // Collect all unique fragments and their DTOs
        for (Context ctx : ch.getHistory()) {
            // Process editable and readonly files -> ReferencedFragmentDto
            ctx.editableFiles().forEach(fragment -> {
                if (!collectedReferencedDtos.containsKey(fragment.id())) {
                    collectedReferencedDtos.put(fragment.id(), (ReferencedFragmentDto) DtoMapper.toReferencedFragmentDto(fragment));
                    if (fragment instanceof FrozenFragment ff && !ff.isText()) {
                        imageDomainFragments.add(ff);
                    }
                }
            });
            ctx.readonlyFiles().forEach(fragment -> {
                if (!collectedReferencedDtos.containsKey(fragment.id())) {
                    collectedReferencedDtos.put(fragment.id(), (ReferencedFragmentDto) DtoMapper.toReferencedFragmentDto(fragment));
                    if (fragment instanceof FrozenFragment ff && !ff.isText()) {
                        imageDomainFragments.add(ff);
                    }
                }
            });

            // Process virtual fragments -> VirtualFragmentDto or TaskFragmentDto
            ctx.virtualFragments().forEach(vf -> {
                if (vf instanceof ContextFragment.SearchFragment sf) {
                    // SearchFragment should go to virtual DTOs, not task DTOs
                    if (!collectedVirtualDtos.containsKey(sf.id())) {
                        collectedVirtualDtos.put(sf.id(), DtoMapper.toVirtualFragmentDto(sf));
                        // SearchFragment is a VirtualFragment, not a FrozenFragment at this point
                        // Image handling will be done if it gets frozen later
                    }
                } else if (vf instanceof ContextFragment.TaskFragment tf) {
                    if (!collectedTaskDtos.containsKey(tf.id())) {
                        collectedTaskDtos.put(tf.id(), DtoMapper.toTaskFragmentDto(tf));
                    }
                } else {
                    if (!collectedVirtualDtos.containsKey(vf.id())) {
                        collectedVirtualDtos.put(vf.id(), DtoMapper.toVirtualFragmentDto(vf));
                        if (vf instanceof FrozenFragment ff && !ff.isText()) {
                            imageDomainFragments.add(ff);
                        }
                    }
                }
            });

            // Process task history logs -> TaskFragmentDto
            ctx.getTaskHistory().forEach(te -> {
                if (te.log() != null && !collectedTaskDtos.containsKey(te.log().id())) {
                    collectedTaskDtos.put(te.log().id(), DtoMapper.toTaskFragmentDto(te.log()));
                }
            });

            // Process parsed output -> TaskFragmentDto
            if (ctx.getParsedOutput() != null && !collectedTaskDtos.containsKey(ctx.getParsedOutput().id())) {
                collectedTaskDtos.put(ctx.getParsedOutput().id(), DtoMapper.toTaskFragmentDto(ctx.getParsedOutput()));
            }
        }

        try (var zos = new ZipOutputStream(Files.newOutputStream(target))) {
            // Write fragments-v1.json
            var allFragmentsDto = new AllFragmentsDto(V1_FORMAT_VERSION, collectedReferencedDtos, collectedVirtualDtos, collectedTaskDtos);
            byte[] fragmentsJsonBytes = objectMapper.writeValueAsBytes(allFragmentsDto);
            ZipEntry fragmentsEntry = new ZipEntry(V1_FRAGMENTS_FILENAME);
            zos.putNextEntry(fragmentsEntry);
            zos.write(fragmentsJsonBytes);
            zos.closeEntry();

            // Write contexts.jsonl
            var contextsJsonlContent = new StringBuilder();
            for (Context ctx : ch.getHistory()) {
                var editableIds = ctx.editableFiles().map(ContextFragment::id).toList(); // Ensure ContextFragment is imported
                var readonlyIds = ctx.readonlyFiles().map(ContextFragment::id).toList(); // Ensure ContextFragment is imported
                var virtualIds = ctx.virtualFragments().map(ContextFragment.VirtualFragment::id).toList(); // Ensure ContextFragment is imported
                var taskEntryRefs = ctx.getTaskHistory().stream()
                        .map(te -> new TaskEntryRefDto( // Assumes FragmentDtos.* imported
                                te.sequence(),
                                te.log() != null ? te.log().id() : null,
                                te.summary()))
                        .toList();
                Integer parsedOutputId = ctx.getParsedOutput() != null ? ctx.getParsedOutput().id() : null;

                var compactDto = new CompactContextDto( // Assumes FragmentDtos.* imported
                        editableIds, readonlyIds, virtualIds, taskEntryRefs, parsedOutputId, summarizeAction(ctx));
                contextsJsonlContent.append(objectMapper.writeValueAsString(compactDto)).append('\n');
            }
            byte[] contextsJsonlBytes = contextsJsonlContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ZipEntry contextsEntry = new ZipEntry(V1_CONTEXTS_FILENAME);
            zos.putNextEntry(contextsEntry);
            zos.write(contextsJsonlBytes);
            zos.closeEntry();

            // Write images
            for (FrozenFragment ff : imageDomainFragments) {
                byte[] imageBytes = ff.imageBytesContent();
                if (imageBytes != null && imageBytes.length > 0) {
                    try {
                        ZipEntry entry = new ZipEntry(IMAGES_DIR_PREFIX + ff.id() + ".png");
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(imageBytes.length);
                        entry.setCompressedSize(imageBytes.length);
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

    public static ContextHistory readZip(Path zip, IContextManager mgr) throws IOException {
        if (!Files.exists(zip)) {
            logger.warn("History zip file not found: {}. Returning empty history.", zip);
            return new ContextHistory();
        }

        boolean hasV1Fragments = false;
        boolean hasV0History = false;

        // Peek into the zip to determine format
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V1_FRAGMENTS_FILENAME)) {
                    hasV1Fragments = true;
                    break; // Found V1 marker, no need to check further for V0 marker in this pass
                }
                if (entry.getName().equals(V0_HISTORY_FILENAME)) {
                    hasV0History = true;
                    // Don't break, V1 marker might still appear if zip is malformed/mixed
                }
            }
        } catch (IOException e) {
            logger.error("Error peeking into zip file {}: {}", zip, e.getMessage());
            // Treat as if no format detected, will fall through to empty history
        }

        ContextHistory ch;
        int maxId = 0;

        if (hasV1Fragments) {
            logger.debug("Reading V1 format history from {}", zip);
            ch = readHistoryV1(zip, mgr);
            if (!ch.getHistory().isEmpty()) {
                 // For V1, max ID can be derived from the initially loaded AllFragmentsDto,
                 // which is more efficient than iterating through all contexts again.
                 // This calculation will be done inside readHistoryV1.
                 // Here, we retrieve it if readHistoryV1 calculated it and made it available.
                 // For simplicity now, just recalculate if not directly available.
                maxId = ch.getHistory().stream()
                          .mapToInt(Context::getMaxId) // Ensure Context.getMaxId exists and is accurate
                          .max().orElse(0);

                // A more direct way for V1 if AllFragmentsDto was accessible here:
                // maxId = allFragmentsDto.referenced().keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
                // maxId = Math.max(maxId, allFragmentsDto.virtual().keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
                // maxId = Math.max(maxId, allFragmentsDto.task().keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
            }
        } else if (hasV0History) {
            logger.debug("Reading V0 format history from {}", zip);
            ch = readHistoryV0(zip, mgr);
             if (!ch.getHistory().isEmpty()) {
                maxId = ch.getHistory().stream()
                          .mapToInt(Context::getMaxId)
                          .max().orElse(0);
            }
        } else {
            logger.warn("History zip file {} does not contain known history format (neither {} nor {} found). Returning empty history.",
                        zip, V1_FRAGMENTS_FILENAME, V0_HISTORY_FILENAME);
            return new ContextHistory();
        }
        
        if (maxId > 0) {
            ContextFragment.setNextId(maxId + 1);
            logger.debug("Set next fragment ID to {}", maxId + 1);
        }
        return ch;
    }

    private static ContextHistory readHistoryV0(Path zip, IContextManager mgr) throws IOException {
        java.util.List<ContextDto> contextDtoList = new java.util.ArrayList<>();
        Map<Integer, byte[]> images = new HashMap<>();

        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V0_HISTORY_FILENAME)) {
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zis, java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            try {
                                contextDtoList.add(objectMapper.readValue(line, ContextDto.class));
                            } catch (Exception e) {
                                logger.error("Failed to parse V0 ContextDto from line: {}", line, e);
                                throw new IOException("Failed to parse V0 ContextDto from line: " + line, e);
                            }
                        }
                    }
                } else if (entry.getName().startsWith(IMAGES_DIR_PREFIX)) {
                    try {
                        int id = idFromName(entry.getName());
                        images.put(id, zis.readAllBytes());
                    } catch (NumberFormatException e) {
                        logger.warn("Could not parse fragment ID from image entry name (V0): {}", entry.getName(), e);
                    }
                }
            }
        }

        if (contextDtoList.isEmpty() && images.isEmpty()) {
            logger.warn("V0 history file {} was empty or {} not found/empty. Returning empty history.", zip, V0_HISTORY_FILENAME);
            return new ContextHistory();
        }
        var historyDto = new HistoryDto(contextDtoList);
        return DtoMapper.fromHistoryDto(historyDto, mgr, images);
    }

    private static ContextHistory readHistoryV1(Path zip, IContextManager mgr) throws IOException {
        AllFragmentsDto allFragmentsDto = null;
        java.util.List<String> compactContextDtoLines = new java.util.ArrayList<>();
        Map<Integer, byte[]> images = new HashMap<>();

        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(V1_FRAGMENTS_FILENAME)) {
                    byte[] fragmentJsonBytes = zis.readAllBytes();
                    allFragmentsDto = objectMapper.readValue(fragmentJsonBytes, AllFragmentsDto.class);
                    zis.closeEntry();
                    if (allFragmentsDto.version() != V1_FORMAT_VERSION) {
                        logger.error("Unsupported V1 fragments version: {}. Expected {}. Cannot load history.", allFragmentsDto.version(), V1_FORMAT_VERSION);
                        return new ContextHistory();
                    }
                } else if (entry.getName().equals(V1_CONTEXTS_FILENAME)) {
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zis, java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            compactContextDtoLines.add(line);
                        }
                    }
                } else if (entry.getName().startsWith(IMAGES_DIR_PREFIX)) {
                    try {
                        int id = idFromName(entry.getName());
                        images.put(id, zis.readAllBytes());
                    } catch (NumberFormatException e) {
                        logger.warn("Could not parse fragment ID from image entry name (V1): {}", entry.getName(), e);
                    }
                }
            }
        }

        if (allFragmentsDto == null) {
            logger.error("V1 history file {} is missing {}. Cannot load history.", zip, V1_FRAGMENTS_FILENAME);
            return new ContextHistory();
        }
        if (compactContextDtoLines.isEmpty() && !allFragmentsDto.referenced().isEmpty()) { // Allow empty history if there were no contexts, but fragments must exist
             logger.warn("V1 history file {} has fragments but is missing {} or it's empty.", zip, V1_CONTEXTS_FILENAME);
             // Proceeding will result in an empty ContextHistory if no context lines, which is acceptable.
        }

        Map<Integer, ContextFragment> fragmentCache = new HashMap<>();
        // Populate cache from AllFragmentsDto
        allFragmentsDto.referenced().forEach((id, dto) -> fragmentCache.put(id, DtoMapper.fromReferencedFragmentDto(dto, mgr, images, fragmentCache)));
        allFragmentsDto.virtual().forEach((id, dto) -> fragmentCache.put(id, DtoMapper.fromVirtualFragmentDto(dto, mgr, images, fragmentCache)));
        allFragmentsDto.task().forEach((id, dto) -> fragmentCache.put(id, DtoMapper.fromTaskFragmentDto(dto, mgr, fragmentCache)));


        ContextHistory ch = new ContextHistory();
        java.util.List<Context> contexts = new java.util.ArrayList<>();
        for (String line : compactContextDtoLines) {
            try {
                CompactContextDto compactDto = objectMapper.readValue(line, CompactContextDto.class);
                contexts.add(DtoMapper.fromCompactDto(compactDto, mgr, fragmentCache, images));
            } catch (Exception e) {
                logger.error("Failed to parse V1 CompactContextDto from line: {}", line, e);
                throw new IOException("Failed to parse V1 CompactContextDto from line: " + line, e);
            }
        }

        if (!contexts.isEmpty()) {
            ch.setInitialContext(contexts.get(0));
            for (int i = 1; i < contexts.size(); i++) {
                ch.addFrozenContextAndClearRedo(contexts.get(i));
            }
        }
        return ch;
    }

    private static String summarizeAction(Context ctx) {
        try {
            return ctx.action.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return "(Summary Unavailable)";
        } catch (Exception e) {
            return "(Error retrieving summary: " + e.getMessage() + ")";
        }
    }

    private static int idFromName(String entryName) {
        // entryName is like "images/123.png"
        String nameWithoutPrefix = entryName.substring(IMAGES_DIR_PREFIX.length());
        String idStr = nameWithoutPrefix.substring(0, nameWithoutPrefix.lastIndexOf('.'));
        return Integer.parseInt(idStr);
    }
}
