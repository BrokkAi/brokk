package ai.brokk.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Utility for discovering GPG keys by invoking the gpg binary.
 */
@NullMarked
public final class GpgKeyUtil {
    private static final Logger logger = LogManager.getLogger(GpgKeyUtil.class);

    public record GpgKey(String id, String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    private GpgKeyUtil() {}

    /**
     * Lists secret keys available in GPG.
     *
     * <p>We prefer {@code --with-colons} for parsing stability (machine-readable), and also request long key ids
     * ({@code --keyid-format=long}) so the persisted identifier is stable and matches typical git configs.
     *
     * @return A list of discovered keys.
     */
    public static List<GpgKey> listSecretKeys() {
        try {
            Process process = new ProcessBuilder(
                            "gpg",
                            "--list-secret-keys",
                            "--with-colons",
                            "--fixed-list-mode",
                            "--keyid-format=long")
                    .redirectErrorStream(true)
                    .start();

            List<String> lines;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                lines = reader.lines().toList();
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.debug("gpg --list-secret-keys exited with code {}", exitCode);
            }
            return parseColonsOutput(lines);
        } catch (Exception e) {
            logger.debug("Failed to list GPG secret keys: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Parses the output of 'gpg --with-colons'.
     * Documentation: https://github.com/gpg/gnupg/blob/master/doc/DETAILS#general-colon-listing-format
     */
    public static List<GpgKey> parseColonsOutput(List<String> lines) {
        Map<String, String> keyIdToDisplay = new LinkedHashMap<>();
        String currentKeyId = "";

        for (String line : lines) {
            String[] parts = line.split(":", -1);
            if (parts.length == 0) {
                continue;
            }

            String recordType = parts[0];

            if ("sec".equals(recordType)) {
                String keyId = getField(parts, 4);
                currentKeyId = keyId;
                if (!keyId.isEmpty()) {
                    keyIdToDisplay.putIfAbsent(keyId, keyId);
                }
                continue;
            }

            if ("uid".equals(recordType) && !currentKeyId.isEmpty()) {
                String userId = extractUserId(parts);
                if (!userId.isEmpty()) {
                    String currentDisplay = keyIdToDisplay.getOrDefault(currentKeyId, currentKeyId);
                    if (currentDisplay.equals(currentKeyId)) {
                        keyIdToDisplay.put(currentKeyId, userId + " (" + currentKeyId + ")");
                    }
                }
            }
        }

        return keyIdToDisplay.entrySet().stream()
                .map(e -> new GpgKey(e.getKey(), e.getValue()))
                .toList();
    }

    private static String extractUserId(String[] parts) {
        // Field 10 (index 9) is the documented "user id" for uid records. Some gpg variants/flags can effectively
        // shift this; we tolerate index 8 as well (matches our fixture) and then fall back to a best-effort scan.
        String field9 = getField(parts, 9);
        if (!field9.isBlank()) {
            return field9;
        }
        String field8 = getField(parts, 8);
        if (!field8.isBlank()) {
            return field8;
        }

        return Arrays.stream(parts)
                .skip(7)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String getField(String[] parts, int index) {
        if (index < 0 || index >= parts.length) {
            return "";
        }
        return parts[index];
    }
}
