package ai.brokk.util.migrationv4;

import ai.brokk.IContextManager;
import ai.brokk.context.ContextHistory;
import ai.brokk.util.HistoryIo;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles migration of V3 history files to the latest version. This class contains V3 DTOs and mapping logic, keeping
 * it separate from the current version's logic.
 */
public class HistoryV4Migrator {

    private static final Logger logger = LogManager.getLogger(HistoryV4Migrator.class);

    private static final Set<String> V3_HISTORY_FILES = Set.of(
            "fragments-v3.json",
            "contexts.jsonl",
            "content_metadata.json",
            "reset_edges.json",
            "git_states.json",
            "entry_infos.json");
    private static final String CONTENT_DIR_PREFIX = "content/";

    public static void migrate(Path zip, IContextManager mgr) throws IOException {
        logger.info("Migrating history file to V4 format: {}", zip);

        var otherFiles = readOtherFiles(zip);

        var history = V3_HistoryIo.readZip(zip, mgr);
        if (!history.getHistory().isEmpty()) {
            // Verify that no FrozenFragment objects survived migration
            verifyNoFrozenFragments(history);

            HistoryIo.writeZip(history, zip); // This will overwrite with V4 format

            if (!otherFiles.isEmpty()) {
                writeOtherFiles(zip, otherFiles);
            }
            logger.info("Migration successful for: {}", zip);
        } else {
            logger.warn("Migration resulted in empty history for: {}. The original file is kept.", zip);
        }
    }

    private static void verifyNoFrozenFragments(ContextHistory history) {
        for (var ctx : history.getHistory()) {
            var frozenFragments = ctx.allFragments()
                    .filter(f -> f instanceof ai.brokk.context.FrozenFragment)
                    .toList();
            if (!frozenFragments.isEmpty()) {
                var frozenIds = frozenFragments.stream()
                        .map(ai.brokk.context.ContextFragment::id)
                        .toList();
                throw new IllegalStateException(
                        "V3 migration produced FrozenFragment objects that should have been unfrozen. "
                                + "Frozen fragment IDs: " + frozenIds + ". "
                                + "This indicates incomplete metadata or missing unfreezing logic.");
            }
        }
        logger.debug("Verified: No FrozenFragment objects in migrated history");
    }

    private static Map<String, byte[]> readOtherFiles(Path zip) throws IOException {
        var otherFiles = new HashMap<String, byte[]>();
        try (var zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();

                if (!V3_HISTORY_FILES.contains(name) && !name.startsWith(CONTENT_DIR_PREFIX)) {
                    try (var is = zf.getInputStream(entry)) {
                        otherFiles.put(name, is.readAllBytes());
                    }
                }
            }
        }
        return otherFiles;
    }

    private static void writeOtherFiles(Path zipPath, Map<String, byte[]> otherFiles) throws IOException {
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of())) {
            for (var entry : otherFiles.entrySet()) {
                Path pathInZip = fs.getPath(entry.getKey());
                if (pathInZip.getParent() != null) {
                    Files.createDirectories(pathInZip.getParent());
                }
                Files.write(
                        pathInZip, entry.getValue(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
    }
}
