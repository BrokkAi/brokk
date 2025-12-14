package ai.brokk.ctl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Manages the per-user shared control key (ctl.key).
 *
 * Responsibilities:
 * - load the key if present
 * - generate and persist a new key atomically if missing
 * - attempt to set user-only file permissions on POSIX systems
 */
public final class CtlKeyManager {
    private static final int KEY_BYTES = 32;
    private final CtlConfigPaths paths;
    private final SecureRandom rng = new SecureRandom();

    public CtlKeyManager(CtlConfigPaths paths) {
        this.paths = Objects.requireNonNull(paths);
    }

    /**
     * Load the key if present, otherwise generate, persist, and return a new key.
     *
     * The created file is written atomically where possible and best-effort file
     * permissions are applied on POSIX systems.
     */
    public synchronized String loadOrCreateKey() throws IOException {
        Optional<String> existing = readKey();
        if (existing.isPresent()) {
            return existing.get();
        }

        String key = generateKey();
        writeKeyAtomically(key);
        applyOwnerOnlyPermissions(paths.getCtlKeyPath());
        return key;
    }

    /**
     * Attempt to read the key file. Returns empty if absent or unreadable.
     */
    public Optional<String> readKey() throws IOException {
        Path keyPath = paths.getCtlKeyPath();
        if (!Files.exists(keyPath)) {
            return Optional.empty();
        }
        String content = Files.readString(keyPath, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    /**
     * Generate a URL-safe base64 key without padding.
     */
    private String generateKey() {
        byte[] bytes = new byte[KEY_BYTES];
        rng.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Write the key in an atomic fashion (write temp file in same directory and move).
     */
    private void writeKeyAtomically(String key) throws IOException {
        Path keyPath = paths.getCtlKeyPath();
        Path dir = keyPath.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        // write to a temp file next to the target, then move
        Path temp = Files.createTempFile(dir != null ? dir : Path.of("."), "ctl.key.", ".tmp");
        Files.writeString(temp, key, StandardCharsets.UTF_8);

        try {
            Files.move(temp, keyPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, keyPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Try to set POSIX owner-only permissions (rw-------). Best-effort: ignore failures on non-POSIX file systems.
     */
    private void applyOwnerOnlyPermissions(Path keyPath) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyPath, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Best-effort: scarce environments (Windows, FAT) may not support POSIX perms.
        }
    }
}
