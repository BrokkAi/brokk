package ai.brokk.util.sandbox.linux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class SeccompFilter {

    private static final String RESOURCE_BASE = "/vendor/seccomp/";
    private static final String BPF_FILENAME = "unix-block.bpf";
    private static final String APPLY_SECCOMP_FILENAME = "apply-seccomp";

    private SeccompFilter() {}

    public static @Nullable String getVendorArchitecture() {
        String arch = System.getProperty("os.arch");
        return mapArchitecture(arch);
    }

    public static @Nullable String mapArchitecture(@Nullable String arch) {
        if (arch == null) {
            return null;
        }
        String normalized = arch.toLowerCase(Locale.ROOT);
        if ("amd64".equals(normalized) || "x86_64".equals(normalized)) {
            return "x64";
        }
        if ("aarch64".equals(normalized) || "arm64".equals(normalized)) {
            return "arm64";
        }
        return null;
    }

    public static @Nullable String getBpfResourcePath() {
        String arch = getVendorArchitecture();
        if (arch == null) {
            return null;
        }
        return RESOURCE_BASE + arch + "/" + BPF_FILENAME;
    }

    public static @Nullable String getApplySeccompResourcePath() {
        String arch = getVendorArchitecture();
        if (arch == null) {
            return null;
        }
        return RESOURCE_BASE + arch + "/" + APPLY_SECCOMP_FILENAME;
    }

    public static boolean hasBpfResource() {
        String resourcePath = getBpfResourcePath();
        if (resourcePath == null) {
            return false;
        }
        return SeccompFilter.class.getResource(resourcePath) != null;
    }

    public static boolean hasApplySeccompResource() {
        String resourcePath = getApplySeccompResourcePath();
        if (resourcePath == null) {
            return false;
        }
        return SeccompFilter.class.getResource(resourcePath) != null;
    }

    public static Path extractBpfToTemp() throws IOException {
        String resourcePath = getBpfResourcePath();
        if (resourcePath == null) {
            throw new IOException("Unsupported architecture: " + System.getProperty("os.arch"));
        }

        try (InputStream is = SeccompFilter.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("BPF resource not found: " + resourcePath);
            }

            Path tempDir = Files.createTempDirectory("seccomp-");
            Path bpfPath = tempDir.resolve(BPF_FILENAME);
            Files.copy(is, bpfPath, StandardCopyOption.REPLACE_EXISTING);
            return bpfPath;
        }
    }

    public static Path extractApplySeccompToTemp() throws IOException {
        String resourcePath = getApplySeccompResourcePath();
        if (resourcePath == null) {
            throw new IOException("Unsupported architecture: " + System.getProperty("os.arch"));
        }

        try (InputStream is = SeccompFilter.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("apply-seccomp resource not found: " + resourcePath);
            }

            Path tempDir = Files.createTempDirectory("seccomp-");
            Path binaryPath = tempDir.resolve(APPLY_SECCOMP_FILENAME);
            Files.copy(is, binaryPath, StandardCopyOption.REPLACE_EXISTING);

            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(binaryPath);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(binaryPath, perms);
            } catch (UnsupportedOperationException e) {
                // Some filesystems (e.g. non-POSIX) do not support POSIX permissions;
                // executable bit may already be set.
            }

            return binaryPath;
        }
    }
}
