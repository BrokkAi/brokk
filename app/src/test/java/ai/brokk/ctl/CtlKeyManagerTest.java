package ai.brokk.ctl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import java.nio.file.attribute.PosixFilePermission;

import static org.junit.jupiter.api.Assertions.*;

class CtlKeyManagerTest {

    @Test
    void createsAndReadsKeyAtomically() throws Exception {
        Path tmp = Files.createTempDirectory("brokk-key-test");
        try {
            CtlConfigPaths p = CtlConfigPaths.forBaseConfigDir(tmp);
            CtlKeyManager m = new CtlKeyManager(p);

            // first call should generate a key and persist it
            String key1 = m.loadOrCreateKey();
            assertNotNull(key1);
            assertFalse(key1.isBlank());

            Path keyPath = p.getCtlKeyPath();
            assertTrue(Files.exists(keyPath));
            String onDisk = Files.readString(keyPath).trim();
            assertEquals(key1, onDisk);

            // second call should read the same key
            String key2 = m.loadOrCreateKey();
            assertEquals(key1, key2);

            // readKey should return same
            Optional<String> maybe = m.readKey();
            assertTrue(maybe.isPresent());
            assertEquals(key1, maybe.get());

            // On POSIX systems the file permissions should be owner-only (best-effort)
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyPath);
                assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
                assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
                assertFalse(perms.contains(PosixFilePermission.GROUP_READ) && perms.contains(PosixFilePermission.GROUP_WRITE));
            } catch (UnsupportedOperationException ex) {
                // non-POSIX FS (Windows) - acceptable
            }
        } finally {
            // cleanup
            try { Files.walk(tmp).sorted((a,b)->b.compareTo(a)).forEach(path -> {try{Files.deleteIfExists(path);}catch(Exception ignored){}}); } catch(Exception ignored){}
        }
    }
}
