package ai.brokk.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Utility for managing global secrets (e.g., API keys, tokens) in a read-protected file.
 */
@NullMarked
public final class GlobalSecrets {
    private static final Logger logger = LogManager.getLogger(GlobalSecrets.class);
    private static final String SECRETS_FILE_NAME = "brokk.secrets.properties";
    private static volatile @Nullable Properties cachedProps;

    private GlobalSecrets() {}

    private static Path getSecretsFile() {
        return BrokkConfigPaths.getGlobalConfigDir().resolve(SECRETS_FILE_NAME);
    }

    private static synchronized Properties loadProps() {
        if (cachedProps != null) {
            return (Properties) cachedProps.clone();
        }

        Properties props = new Properties();
        Path path = getSecretsFile();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                props.load(reader);
            } catch (IOException e) {
                logger.error("Failed to load global secrets from {}: {}", path, e.getMessage());
            }
        }
        cachedProps = (Properties) props.clone();
        return props;
    }

    private static synchronized void saveProps(Properties props) {
        Path path = getSecretsFile();
        try {
            Files.createDirectories(path.getParent());

            // Ensure restrictive permissions if the file doesn't exist yet
            if (!Files.exists(path)) {
                ensureRestrictivePermissions(path);
            }

            AtomicWrites.atomicSaveProperties(path, props, "Brokk Global Secrets - Read Protected");

            // Re-apply permissions after atomic move to ensure they are correct on POSIX
            ensureRestrictivePermissions(path);

            cachedProps = (Properties) props.clone();
        } catch (IOException e) {
            logger.error("Failed to save global secrets to {}: {}", path, e.getMessage());
        }
    }

    private static void ensureRestrictivePermissions(Path path) {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(path, perms);
            } catch (IOException e) {
                logger.warn("Failed to set POSIX permissions on {}: {}", path, e.getMessage());
            }
        }
    }

    public static String getSecret(String key, String defaultValue) {
        return loadProps().getProperty(key, defaultValue);
    }

    public static void setSecret(String key, String value) {
        Properties props = loadProps();
        if (value.isBlank()) {
            props.remove(key);
        } else {
            props.setProperty(key, value.trim());
        }
        saveProps(props);
    }

    /** For testing purposes only. */
    public static synchronized void resetForTests() {
        cachedProps = null;
    }
}
