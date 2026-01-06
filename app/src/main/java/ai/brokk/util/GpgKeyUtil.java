package ai.brokk.util;

import ai.brokk.git.gpg.ExternalGpg;
import ai.brokk.git.gpg.ExternalProcessRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.CanceledException;
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
        String gpg = ExternalGpg.getGpg();
        if (gpg == null) {
            return List.of();
        }

        ProcessBuilder pb = new ProcessBuilder(gpg, "--list-secret-keys", "--with-colons");
        List<GpgKey> keys = new ArrayList<>();

        try {
            ExternalProcessRunner.run(
                    pb,
                    null,
                    (buffer) -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                buffer.openInputStream(),
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
                        } catch (IOException e) {
                            logger.error("Error parsing GPG output", e);
                        }
                    },
                    (errBuffer) -> {
                        // Log stderr if needed, but --with-colons is usually clean
                    });
        } catch (CanceledException | IOException e) {
            logger.warn("Failed to list GPG secret keys", e);
        }

        return List.copyOf(keys);
    }
}
