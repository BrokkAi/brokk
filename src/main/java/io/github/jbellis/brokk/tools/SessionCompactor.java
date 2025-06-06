package io.github.jbellis.brokk.tools;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.util.HistoryIo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class SessionCompactor {
    private static final Logger logger = LogManager.getLogger(SessionCompactor.class);

    public static void main(String[] args) {
        Path projectHome = Path.of(".");
        Path brokkDir = projectHome.resolve(".brokk"); // Standard Brokk config directory
        Path sessionsDir = brokkDir.resolve("sessions");
        Path sessionsTestDir = brokkDir.resolve("sessions-test");

        if (!Files.isDirectory(sessionsDir)) {
            logger.error("Source sessions directory does not exist: {}", sessionsDir);
            return;
        }

        try {
            Files.createDirectories(sessionsTestDir);
            logger.info("Target directory for compacted sessions: {}", sessionsTestDir);
        } catch (IOException e) {
            logger.error("Failed to create target directory: {}", sessionsTestDir, e);
            return;
        }

        // Dummy context manager for reading old format sessions
        IContextManager dummyManager = DummyContextManager.INSTANCE;

        try (var sessionFiles = Files.list(sessionsDir)) {
            sessionFiles
                    .filter(path -> path.toString().endsWith(".zip"))
                    .forEach(srcZipPath -> {
                        Path dstZipPath = sessionsTestDir.resolve(srcZipPath.getFileName());
                        logger.info("Processing session: {}", srcZipPath.getFileName());
                        try {
                            ContextHistory history = HistoryIo.readZip(srcZipPath, dummyManager);
                            if (history.getHistory().isEmpty()) {
                                logger.warn("Session {} was empty or failed to load, copying original.", srcZipPath.getFileName());
                                Files.copy(srcZipPath, dstZipPath, StandardCopyOption.REPLACE_EXISTING);
                            } else {
                                HistoryIo.writeZip(history, dstZipPath); // Write with new format
                                logger.info("Successfully compacted {} to {}", srcZipPath.getFileName(), dstZipPath);
                            }
                        } catch (IOException e) {
                            logger.error("Failed to compact session {}: {}", srcZipPath.getFileName(), e.getMessage(), e);
                            try {
                                logger.warn("Copying original file {} due to error.", srcZipPath.getFileName());
                                Files.copy(srcZipPath, dstZipPath, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException copyEx) {
                                logger.error("Failed to copy original file {} after error: {}", srcZipPath.getFileName(), copyEx.getMessage(), copyEx);
                            }
                        }
                    });
            logger.info("Session compaction process finished.");
        } catch (IOException e) {
            logger.error("Failed to list session files in {}: {}", sessionsDir, e.getMessage(), e);
        }
    }

    private enum DummyContextManager implements IContextManager {
        INSTANCE;

        @Override
        public IProject getProject() {
            return new MainProject(Path.of("."));
        }

        @Override
        public IAnalyzer getAnalyzer() {
            return null; // Analyzer interactions are less likely during simple history deserialization
        }
    }
}
