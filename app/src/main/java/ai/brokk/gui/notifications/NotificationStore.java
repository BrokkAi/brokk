package ai.brokk.gui.notifications;

import ai.brokk.IConsoleIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple file-backed notification store.
 *
 * Persistence format (version 2):
 *   2|ROLE|timestamp|base64(message)
 *
 * The store writes up to 100 most-recent notifications (newest first).
 *
 * The caller is responsible for calling saveAsync when running off the EDT.
 */
public final class NotificationStore {
    private static final Logger logger = LogManager.getLogger(NotificationStore.class);
    private final Path notificationsFile;

    /**
     * Construct with the file to use for persistence.
     *
     * @param notificationsFile path to file (will be created if missing on save)
     */
    public NotificationStore(Path notificationsFile) {
        this.notificationsFile = notificationsFile;
    }

    /**
     * Loads notifications from disk. Returns an empty list on error or if file missing.
     * This method performs blocking IO and should be called off the EDT if used in UI startup.
     */
    public List<NotificationEntry> load() {
        try {
            if (!Files.exists(notificationsFile)) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(notificationsFile, StandardCharsets.UTF_8);
            List<NotificationEntry> out = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                String[] parts = line.split("\\|", 4);
                if (parts.length < 4) continue;
                if (!"2".equals(parts[0])) continue; // skip older or unknown versions

                IConsoleIO.NotificationRole role;
                try {
                    role = IConsoleIO.NotificationRole.valueOf(parts[1]);
                } catch (IllegalArgumentException e) {
                    continue;
                }

                long ts;
                try {
                    ts = Long.parseLong(parts[2]);
                } catch (NumberFormatException nfe) {
                    ts = System.currentTimeMillis();
                }

                String message;
                try {
                    byte[] bytes = Base64.getDecoder().decode(parts[3]);
                    message = new String(bytes, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException iae) {
                    message = parts[3];
                }

                out.add(new NotificationEntry(role, message, ts));
            }
            return out;
        } catch (Exception e) {
            logger.warn("Failed to load persisted notifications from {}", notificationsFile, e);
            return List.of();
        }
    }

    /**
     * Saves the provided notifications to disk. The list will be truncated to 100 newest entries and written
     * newest-first. This method performs blocking IO.
     */
    public void save(List<NotificationEntry> entries) {
        try {
            List<String> linesToPersist = entries.stream()
                    .sorted(NotificationEntry.NEWEST_FIRST)
                    .limit(100)
                    .map(n -> {
                        String msgB64 = Base64.getEncoder().encodeToString(n.message.getBytes(StandardCharsets.UTF_8));
                        return "2|" + n.role.name() + "|" + n.timestamp + "|" + msgB64;
                    })
                    .collect(Collectors.toList());
            // Ensure parent directory exists
            Path parent = notificationsFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(
                    notificationsFile,
                    linesToPersist,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warn("Failed to persist notifications to {}", notificationsFile, e);
        }
    }

    /**
     * Asynchronous save using the common ForkJoinPool. Caller may provide a custom executor if desired.
     */
    public CompletableFuture<Void> saveAsync(List<NotificationEntry> entries) {
        return CompletableFuture.runAsync(() -> save(entries));
    }

    /**
     * Convenience to compute a default notifications file path under a given project root:
     * <projectRoot>/.brokk/notifications.txt
     */
    public static Path computeDefaultPath(Path projectRoot) {
        return projectRoot.resolve(".brokk").resolve("notifications.txt");
    }
}
