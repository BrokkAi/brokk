package ai.brokk.util.sandbox.linux;

import ai.brokk.util.sandbox.SandboxConfig;
import ai.brokk.util.sandbox.SandboxUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LinuxSandbox {
    private LinuxSandbox() {}

    public static String wrapCommand(
            String command, SandboxConfig.FilesystemConfig fsConfig, SandboxConfig.LinuxOptions linuxOptions, String shellPath) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be null/blank");
        }
        Objects.requireNonNull(fsConfig, "fsConfig");

        String shell = (shellPath == null || shellPath.isBlank()) ? "/bin/bash" : shellPath;

        List<String> bwrapArgs = new ArrayList<>();
        bwrapArgs.add("--new-session");
        bwrapArgs.add("--die-with-parent");
        bwrapArgs.addAll(buildFilesystemArgs(fsConfig));
        bwrapArgs.add("--dev");
        bwrapArgs.add("/dev");
        bwrapArgs.add("--unshare-pid");
        bwrapArgs.add("--proc");
        bwrapArgs.add("/proc");

        boolean blockUnixSockets = linuxOptions == null || !linuxOptions.allowAllUnixSockets();

        try {
            String sandboxCommand = command;

            if (blockUnixSockets) {
                Path bpfPath = SeccompFilter.extractBpfToTemp();
                Path applySeccompPath = SeccompFilter.extractApplySeccompToTemp();

                String applySeccompCmd =
                        shellQuote(applySeccompPath.toString())
                                + " "
                                + shellQuote(bpfPath.toString())
                                + " "
                                + shellQuote(shell)
                                + " -c "
                                + shellQuote(command);

                sandboxCommand = applySeccompCmd;
            }

            bwrapArgs.add("--");
            bwrapArgs.add(shell);
            bwrapArgs.add("-c");
            bwrapArgs.add(sandboxCommand);

            return "bwrap " + shellQuoteList(bwrapArgs);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare Linux sandbox", e);
        }
    }

    private static List<String> buildFilesystemArgs(SandboxConfig.FilesystemConfig fsConfig) {
        List<String> args = new ArrayList<>();

        List<String> allowWrite = fsConfig.allowWrite();
        boolean hasWriteRestrictions = allowWrite != null && !allowWrite.isEmpty();

        if (hasWriteRestrictions) {
            args.add("--ro-bind");
            args.add("/");
            args.add("/");

            List<String> allowedWritePaths = new ArrayList<>();

            for (String pathPattern : allowWrite) {
                if (pathPattern == null || pathPattern.isBlank()) {
                    continue;
                }

                String normalizedPath = SandboxUtils.normalizePathForSandbox(pathPattern);

                if (normalizedPath.startsWith("/dev/")) {
                    continue;
                }
                if (!Files.exists(Path.of(normalizedPath))) {
                    continue;
                }

                args.add("--bind");
                args.add(normalizedPath);
                args.add(normalizedPath);

                allowedWritePaths.add(normalizedPath);
            }

            for (String pathPattern : fsConfig.denyWrite()) {
                if (pathPattern == null || pathPattern.isBlank()) {
                    continue;
                }

                String normalizedPath = SandboxUtils.normalizePathForSandbox(pathPattern);

                if (normalizedPath.startsWith("/dev/")) {
                    continue;
                }
                if (!Files.exists(Path.of(normalizedPath))) {
                    continue;
                }

                boolean isWithinAllowedPath = false;
                for (String allowedPath : allowedWritePaths) {
                    if (normalizedPath.equals(allowedPath) || normalizedPath.startsWith(allowedPath + "/")) {
                        isWithinAllowedPath = true;
                        break;
                    }
                }

                if (isWithinAllowedPath) {
                    args.add("--ro-bind");
                    args.add(normalizedPath);
                    args.add(normalizedPath);
                }
            }
        } else {
            args.add("--bind");
            args.add("/");
            args.add("/");
        }

        for (String pathPattern : fsConfig.denyRead()) {
            if (pathPattern == null || pathPattern.isBlank()) {
                continue;
            }

            String normalizedPath = SandboxUtils.normalizePathForSandbox(pathPattern);
            Path p = Path.of(normalizedPath);

            if (!Files.exists(p)) {
                continue;
            }

            if (Files.isDirectory(p)) {
                args.add("--tmpfs");
                args.add(normalizedPath);
            } else {
                args.add("--ro-bind");
                args.add("/dev/null");
                args.add(normalizedPath);
            }
        }

        return List.copyOf(args);
    }

    private static String shellQuote(String s) {
        if (s == null) {
            throw new IllegalArgumentException("string must not be null");
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String shellQuoteList(List<String> args) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }

            String arg = args.get(i);
            if (arg == null) {
                out.append("''");
                continue;
            }

            if (arg.startsWith("-")) {
                out.append(arg);
            } else {
                out.append(shellQuote(arg));
            }
        }
        return out.toString();
    }
}
