package io.github.jbellis.brokk.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.context.DtoMapper;
import io.github.jbellis.brokk.context.ContextDto;
import io.github.jbellis.brokk.context.FragmentDtos.HistoryDto;
import io.github.jbellis.brokk.context.FrozenFragment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class HistoryIo {
    private static final Logger logger = LogManager.getLogger(HistoryIo.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.CLOSE_CLOSEABLE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private HistoryIo() {}

    public static void writeZip(ContextHistory ch, Path target) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(target))) {
            // Write history.jsonl - one ContextDto per line
            HistoryDto historyDto = DtoMapper.toHistoryDto(ch);
            var jsonlContent = new StringBuilder();
            for (var contextDto : historyDto.contexts()) {
                jsonlContent.append(objectMapper.writeValueAsString(contextDto)).append('\n');
            }
            byte[] jsonlBytes = jsonlContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ZipEntry jsonlEntry = new ZipEntry("history.jsonl");
            zos.putNextEntry(jsonlEntry);
            zos.write(jsonlBytes);
            zos.closeEntry();

            // Write images - collect unique FrozenFragments first to avoid duplicate ZIP entries
            var uniqueImageFragments = ch.getHistory().stream()
                .flatMap(Context::allFragments)
                .filter(f -> !f.isText() && f instanceof FrozenFragment)
                .map(f -> (FrozenFragment) f)
                .collect(java.util.stream.Collectors.toSet());

            for (FrozenFragment ff : uniqueImageFragments) {
                byte[] imageBytes = ff.imageBytesContent();
                if (imageBytes != null && imageBytes.length > 0) {
                    try {
                        ZipEntry entry = new ZipEntry("images/" + ff.id() + ".png");
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
            logger.error("Failed to write history zip file: {}", target, e);
            throw new IOException("Failed to write history zip file: " + target, e);
        }
    }

    public static ContextHistory readZip(Path zip, IContextManager mgr) throws IOException {
        HistoryDto dto = null;
        Map<Integer, byte[]> images = new HashMap<>();

        if (!Files.exists(zip)) {
            logger.warn("History zip file not found: {}. Returning empty history.", zip);
            return new ContextHistory(); // Or perhaps a new history with initial context from mgr
        }

        java.util.List<ContextDto> contextDtoList = new java.util.ArrayList<>();
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("history.jsonl")) {
                    // Stream read JSONL format - one ContextDto per line
                    // Do not close the reader here, as it would close the underlying ZipInputStream
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zis, java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            try {
                                contextDtoList.add(objectMapper.readValue(line, ContextDto.class));
                            } catch (Exception e) {
                                logger.error("Failed to parse ContextDto from line: {}", line, e);
                                throw new IOException("Failed to parse ContextDto from line: " + line, e);
                            }
                        }
                    }
                    // BufferedReader has consumed the current entry.
                    // ZipInputStream will handle advancing to the next entry or closing.
                } else if (entry.getName().startsWith("images/")) {
                    try {
                        int id = idFromName(entry.getName());
                        images.put(id, zis.readAllBytes()); // Consumes this entry's stream
                    } catch (NumberFormatException e) {
                        logger.warn("Could not parse fragment ID from image entry name: {}", entry.getName(), e);
                    }
                    // zis.closeEntry() for image entry also handled by getNextEntry() or close().
                }
                // No explicit zis.closeEntry() needed here, as ZipInputStream handles it.
            }
        }

        if (contextDtoList.isEmpty() && images.isEmpty()) { // Check if anything was read
             // This could happen if the zip is empty or history.jsonl is missing/empty
            logger.warn("History zip file {} was empty or history.jsonl not found/empty. Returning empty history.", zip);
            return new ContextHistory();
        }
        // If contextDtoList is populated, create the HistoryDto
        dto = new HistoryDto(contextDtoList);
        return DtoMapper.fromHistoryDto(dto, mgr, images);
    }

    private static int idFromName(String entryName) {
        // entryName is like "images/123.png"
        String nameWithoutPrefix = entryName.substring("images/".length());
        String idStr = nameWithoutPrefix.substring(0, nameWithoutPrefix.lastIndexOf('.'));
        return Integer.parseInt(idStr);
    }
}
