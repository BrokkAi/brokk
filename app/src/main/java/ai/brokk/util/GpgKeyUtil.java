package ai.brokk.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.gpg.signing.GpgBinary;
import org.eclipse.jgit.util.SystemReader;
import org.jetbrains.annotations.Blocking;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class GpgKeyUtil {
    private static final Logger logger = LogManager.getLogger(GpgKeyUtil.class);

    public record GpgKey(String id, String userId) {}

    /**
     * Lists secret keys available in the GPG keyring using --with-colons for stable parsing.
     */
    @Blocking
    public static List<GpgKey> listSecretKeys() {
        Path gpgPath;
        try {
            gpgPath = new GpgBinary(null).getPath();
        } catch (IOException e) {
            logger.warn("GPG binary not found: {}", e.getMessage());
            return List.of();
        }

        ProcessBuilder pb = new ProcessBuilder(gpgPath.toString(), "--list-secret-keys", "--with-colons");
        List<GpgKey> keys = new ArrayList<>();

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(),
                    SystemReader.getInstance().getDefaultCharset()))) {

                String line;
                String currentKeyId = null;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":", -1);
                    if (parts.length < 1) continue;

                    String recordType = parts[0];
                    switch (recordType) {
                        case "sec" -> {
                            // Secret key record. Field 5 is the KeyID (long form or fingerprint)
                            if (parts.length > 4) {
                                currentKeyId = parts[4];
                            }
                        }
                        case "uid" -> {
                            // User ID record. Field 10 is the User ID string.
                            if (currentKeyId != null && parts.length > 9) {
                                keys.add(new GpgKey(currentKeyId, parts[9]));
                                // Reset to avoid attaching same UID to multiple subkeys or vice versa
                                // if logic gets more complex, but for simple listing this works.
                                currentKeyId = null;
                            }
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("GPG exited with code {}", exitCode);
            }
        } catch (IOException | InterruptedException e) {
            logger.warn("Failed to list GPG secret keys", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        return List.copyOf(keys);
    }
}
