package io.github.jbellis.brokk.util.migrationv3;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.util.HistoryIo;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Handles migration of V1 history files to the latest version. This class contains V1 DTOs and mapping logic, keeping
 * it separate from the current version's logic.
 */
public class HistoryV3Migrator {
    private static final Logger logger = LogManager.getLogger(HistoryV3Migrator.class);

    public static void migrate(Path zip, IContextManager mgr) throws IOException {
        logger.info("Migrating history file to V3 format: {}", zip);

        var manifestBytes = readManifest(zip);

        var history = V2_HistoryIo.readZip(zip, mgr);
        if (history != null) {
            HistoryIo.writeZip(history, zip); // This will overwrite with V3 format

            if (manifestBytes != null) {
                writeManifest(zip, manifestBytes);
            }
            logger.info("Migration successful for: {}", zip);
        } else {
            logger.warn("Migration resulted in empty history for: {}. The original file is kept.", zip);
        }
    }

    private static @Nullable byte[] readManifest(Path zip) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("manifest.json")) {
                    return zis.readAllBytes();
                }
            }
        }
        return null;
    }

    private static void writeManifest(Path zipPath, byte[] manifestBytes) throws IOException {
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of())) {
            Path manifestPath = fs.getPath("manifest.json");
            Files.write(manifestPath, manifestBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
