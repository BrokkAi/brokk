package io.github.jbellis.brokk.gui.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.brokk.util.AtomicWrites;
import io.github.jbellis.brokk.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FileBasedTestRunsStore implements TestRunsStore {
    private static final Logger logger = LogManager.getLogger(FileBasedTestRunsStore.class);

    private final Path file;

    public FileBasedTestRunsStore(Path file) {
        this.file = file;
    }

    @Override
    public List<RunRecord> load() {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            String json = Files.readString(file);
            if (json.isBlank()) {
                return List.of();
            }
            ObjectMapper mapper = Json.getMapper();
            var type = mapper.getTypeFactory().constructCollectionType(List.class, RunRecord.class);
            List<RunRecord> runs = mapper.readValue(json, type);
            return runs != null ? runs : List.of();
        } catch (IOException e) {
            logger.warn("Failed to read test runs from {}: {}", file, e.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.error("Unexpected error reading test runs from {}: {}", file, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public void save(List<RunRecord> runs) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = Json.toJson(runs);
            AtomicWrites.atomicOverwrite(file, json);
        } catch (IOException e) {
            logger.error("Failed to write test runs to {}: {}", file, e.getMessage(), e);
        }
    }
}
