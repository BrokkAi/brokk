package ai.brokk.gui.mop.webview;

import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.friwi.jcefmaven.CefAppBuilder;

public class JCefSetup {
    /**
     * Returns a CefAppBuilder for jcefmaven-based initialization.
     * Note: When running under JBR with JCEF, consider using buildNative() instead.
     */
    public static CefAppBuilder builder() {
        Path jcefDir = getJcefDir();
        var builder = new CefAppBuilder();
        builder.setInstallDir(jcefDir.toFile());
        // Default to console progress for downloads during dev/testing
        builder.setProgressHandler(new me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler());
        return builder;
    }

    private static Path getJcefDir() {
        var appDir = System.getProperty("app.dir");
        if (appDir == null) {
            // Dev mode
            return Paths.get("./jcef-bundle");
        }

        // Packaged with Conveyor
        var appDirPath = Paths.get(appDir);
        if (Environment.isMacOs()) {
            var jcefDir = appDirPath.resolve("../Frameworks").normalize();
            if (!Files.exists(jcefDir.resolve("jcef Helper.app"))) {
                throw new IllegalStateException("jcef Helper.app not found");
            }
            return jcefDir;
        } else if (Environment.isWindows()) {
            var jcefDir = appDirPath.resolve("jcef");
            if (!Files.exists(jcefDir.resolve("jcef.dll"))) {
                throw new IllegalStateException("jcef.dll not found");
            }
            return jcefDir;
        } else {
            var jcefDir = appDirPath.resolve("jcef");
            if (!Files.exists(jcefDir.resolve("libjcef.so"))) {
                throw new IllegalStateException("libjcef.so not found");
            }
            return jcefDir;
        }
    }
}
