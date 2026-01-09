package ai.brokk.gui.mop.webview;

import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefSettings;

public class JCefSetup {
    /**
     * Returns a CefAppBuilder for jcefmaven-based initialization.
     * Note: When running under JBR with JCEF, consider using buildNative() instead.
     */
    public static CefAppBuilder builder() {
        Path jcefDir = getJcefDir();
        var builder = new CefAppBuilder();
        builder.setInstallDir(jcefDir.toFile());
        return builder;
    }

    /**
     * Initializes JCEF using JBR's native JCEF (when available).
     * This avoids jcefmaven's dependency on build_meta.json.
     */
    public static CefApp buildNative(CefSettings settings) {
        if (!Environment.isJBR()) {
            throw new IllegalStateException("buildNative() requires JBR runtime");
        }
        // JBR's JCEF requires startup() before getInstance()
        CefApp.startup(new String[] {});
        return CefApp.getInstance(settings);
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
