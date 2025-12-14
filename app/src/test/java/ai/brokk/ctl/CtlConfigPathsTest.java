package ai.brokk.ctl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CtlConfigPathsTest {

    @Test
    void forBaseDirProducesInstancesAndKeyPaths() throws Exception {
        Path tmp = Files.createTempDirectory("brokk-config-test");
        try {
            CtlConfigPaths p = CtlConfigPaths.forBaseConfigDir(tmp);

            Path instances = p.getInstancesDir();
            Path key = p.getCtlKeyPath();

            assertNotNull(instances, "instances dir should not be null");
            assertNotNull(key, "ctl key path should not be null");

            assertEquals("instances", instances.getFileName().toString(), "instances dir name should be 'instances'");
            assertEquals("ctl.key", key.getFileName().toString(), "key filename should be 'ctl.key'");

            // ensure creating directories works
            Path created = p.ensureInstancesDirExists();
            assertTrue(Files.exists(created));
            assertTrue(Files.isDirectory(created));
        } finally {
            // best-effort cleanup
            try { Files.walk(tmp).sorted((a,b)->b.compareTo(a)).forEach(path -> {try{Files.deleteIfExists(path);}catch(Exception ignored){}}); } catch(Exception ignored){}
        }
    }
}
