package io.github.jbellis.brokk.util.migrationv3;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.util.HistoryIo;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles migration of V1 history files to the latest version. This class contains V1 DTOs and mapping logic, keeping
 * it separate from the current version's logic.
 */
public class HistoryV3Migrator {
    private static final Logger logger = LogManager.getLogger(HistoryV3Migrator.class);

    public static void migrate(Path zip, IContextManager mgr) throws IOException {
        logger.info("Migrating history file to V3 format: {}", zip);
        var history = V2_HistoryIo.readZip(zip, mgr);
        if (history != null) {
            HistoryIo.writeZip(history, zip); // This will overwrite with V3 format
            logger.info("Migration successful for: {}", zip);
        } else {
            logger.warn("Migration resulted in empty history for: {}. The original file is kept.", zip);
        }
    }
}
