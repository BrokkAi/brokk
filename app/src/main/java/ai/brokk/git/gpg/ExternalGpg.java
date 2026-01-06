package ai.brokk.git.gpg;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;

public class ExternalGpg {
    private static final Logger logger = LogManager.getLogger(ExternalGpg.class);

    private static final Map<String, String> EXECUTABLES = new ConcurrentHashMap<>();

    public static String getGpg() {
        return get("gpg");
    }

    public static String getGpgSm() {
        return get("gpgsm");
    }

    static String findExecutable(String program) {
        try {
            Path exe = Paths.get(program);
            if (!exe.isAbsolute() && exe.getNameCount() == 1) {
                String resolved = get(program);
                if (resolved != null) {
                    return resolved;
                }
            }
        } catch (InvalidPathException e) {
            logger.warn("Invalid path: {}", program, e);
        }

        return program;
    }

    private static String get(String program) {
        String resolved = EXECUTABLES.computeIfAbsent(program, ExternalGpg::findProgram);
        return resolved.isEmpty() ? null : resolved;
    }

    private static String findProgram(String program) {
        SystemReader system = SystemReader.getInstance();
        String path = system.getenv("PATH");
        String exe = null;
        if (system.isMacOS()) {
            String bash = searchPath(path, "bash");
            if (bash != null) {
                ProcessBuilder process = new ProcessBuilder();
                process.command(bash, "--login", "-c", "which " + program);
                process.directory(FS.DETECTED.userHome());
                String[] result = new String[1];

                try {
                    ExternalProcessRunner.run(
                            process,
                            (InputStream) null,
                            (b) -> {
                                try (BufferedReader r = new BufferedReader(
                                        new InputStreamReader(b.openInputStream(), system.getDefaultCharset()))) {
                                    result[0] = r.readLine();
                                }
                            },
                            null);
                } catch (CanceledException | IOException e) {
                    logger.warn("Cannot search for GPG executable", e);
                }

                exe = result[0];
            }
        }

        if (exe == null) {
            exe = searchPath(path, system.isWindows() ? completeWindowsPath(program) : program);
        }

        return exe == null ? "" : exe;
    }

    private static String completeWindowsPath(String program) {
        String name = (new File(program)).getName();
        return name.equals(program) && name.indexOf('.') < 0 ? program + ".exe" : program;
    }

    private static String searchPath(String path, String name) {
        if (StringUtils.isEmptyOrNull(path)) {
            return null;
        } else {
            for (String p : path.split(File.pathSeparator)) {
                File exe = new File(p, name);

                try {
                    if (exe.isFile() && exe.canExecute()) {
                        return exe.getAbsolutePath();
                    }
                } catch (SecurityException e) {
                    logger.warn("Skipping not accessible path: {}", exe.getPath(), e);
                }
            }

            return null;
        }
    }
}
