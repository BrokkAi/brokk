package ai.brokk.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

final class SystemTempDir {
    private SystemTempDir() {}

    static Path root() {
        Path probe;
        try {
            probe = Files.createTempDirectory("brokk-system-temp-root-probe");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            return Objects.requireNonNull(probe.getParent(), "Temp directory must have a parent");
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
