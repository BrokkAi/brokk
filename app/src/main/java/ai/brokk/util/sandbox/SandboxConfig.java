package ai.brokk.util.sandbox;

import java.util.ArrayList;
import java.util.List;

public record SandboxConfig(FilesystemConfig filesystem, LinuxOptions linuxOptions) {
    public SandboxConfig {
        if (filesystem == null) {
            filesystem = FilesystemConfig.empty();
        }
    }

    public record FilesystemConfig(
            List<String> denyRead, List<String> allowWrite, List<String> denyWrite, boolean allowGitConfig) {
        public FilesystemConfig {
            denyRead = List.copyOf(denyRead == null ? List.of() : denyRead);
            allowWrite = List.copyOf(allowWrite == null ? List.of() : allowWrite);
            denyWrite = List.copyOf(denyWrite == null ? List.of() : denyWrite);
        }

        public static FilesystemConfig empty() {
            return new FilesystemConfig(List.of(), List.of(), List.of(), false);
        }
    }

    public record LinuxOptions(boolean allowAllUnixSockets) {
        public static LinuxOptions defaults() {
            return new LinuxOptions(false);
        }
    }

    public SandboxConfig validate() {
        List<String> errors = new ArrayList<>();

        FilesystemConfig fs = filesystem();
        if (fs == null) {
            errors.add("filesystem must not be null");
        } else {
            addNonBlankListErrors(errors, "filesystem.denyRead", fs.denyRead());
            addNonBlankListErrors(errors, "filesystem.allowWrite", fs.allowWrite());
            addNonBlankListErrors(errors, "filesystem.denyWrite", fs.denyWrite());
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        return this;
    }

    private static void addNonBlankListErrors(List<String> errors, String fieldName, List<String> values) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            if (v == null || v.isBlank()) {
                errors.add(fieldName + "[" + i + "] must not be blank");
            }
        }
    }

    public static SandboxConfig empty() {
        return new SandboxConfig(FilesystemConfig.empty(), LinuxOptions.defaults());
    }
}
