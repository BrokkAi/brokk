package io.github.jbellis.brokk.analyzer.lsp.jdt;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.lsp.LspFileUtilities;
import io.github.jbellis.brokk.analyzer.lsp.LspLanguageClient;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import io.github.jbellis.brokk.analyzer.lsp.SupportedLspServer;
import io.github.jbellis.brokk.gui.dialogs.AnalyzerSettingsPanel.JavaAnalyzerSettingsPanel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.Nullable;

/** Manages a single, shared instance of the JDT Language Server process. This class is a thread-safe singleton. */
public final class SharedJdtLspServer extends LspServer {

    private static final SharedJdtLspServer INSTANCE = new SharedJdtLspServer();
    private @Nullable JdtLanguageClient languageClient;

    private SharedJdtLspServer() {
        super(SupportedLspServer.JDT);
    }

    /**
     * Returns the singleton instance with the given {@link IConsoleIO}.
     *
     * @return the singleton LSP client instance.
     */
    public static SharedJdtLspServer getInstance() {
        return INSTANCE;
    }

    /**
     * Sets the singleton instance with the given {@link IConsoleIO} instance as the IO to use for future diagnostics.
     *
     * @param io the IO instance to use. If `null`, user-facing diagnostics will be disabled.
     */
    public static void setIo(@Nullable IConsoleIO io) {
        if (INSTANCE.languageClient != null) {
            INSTANCE.languageClient.setIo(io);
        }
    }

    @Override
    protected ProcessBuilder createProcessBuilder(Path cache) throws IOException {
        final Path serverHome = LspFileUtilities.unpackLspServer("jdt");
        final Path launcherJar = LspFileUtilities.findFile(serverHome, "org.eclipse.equinox.launcher_");
        final Path configDir = LspFileUtilities.findConfigDir(serverHome);
        final int memoryMB = JavaAnalyzerSettingsPanel.getSavedMemoryValueMb();
        logger.debug("Creating JDT LSP process with a max heap size of {} Mb", memoryMB);
        var javaExec = resolveJavaExecutable();

        if (!Files.isDirectory(cache)) Files.createDirectories(cache);

        return new ProcessBuilder(
                javaExec,
                // Java module system args for compatibility
                "--add-modules=ALL-SYSTEM",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                // Eclipse/OSGi launchers
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dlog.level=ALL",
                // JDT LSP arguments
                "-Djava.import.generatesMetadataFilesAtProjectRoot=false",
                "-DDetectVMInstallsJob.disabled=true",
                // Memory arguments
                "-Xmx" + memoryMB + "m",
                "-Xms100m",
                "-XX:+UseParallelGC",
                "-XX:GCTimeRatio=4",
                "-XX:AdaptiveSizePolicyWeight=90",
                "-XX:+UseStringDeduplication",
                "-Dsun.zip.disableMemoryMapping=true",
                // Running the JAR
                "-jar",
                launcherJar.toString(),
                "-configuration",
                configDir.toString(),
                "-data",
                cache.toString());
    }

    @Override
    protected LspLanguageClient getLanguageClient(
            String language, CountDownLatch serverReadyLatch, Map<String, CountDownLatch> workspaceReadyLatchMap) {
        this.languageClient = new JdtLanguageClient(language, serverReadyLatch, workspaceReadyLatchMap);
        return this.languageClient;
    }

    /** Returns the current language client, or null if not initialized. */
    public @Nullable LanguageClient getLanguageClient() {
        return this.languageClient;
    }

    /**
     * Determines the Java executable to use for launching the JDT language server.
     *
     * <p>Preference order: 1. {@code JAVA_HOME}/bin/java(.exe) 2. First matching {@code java} (or {@code java.exe})
     * found on the system PATH
     *
     * @return absolute path to a usable Java executable
     * @throws IOException if no executable can be located
     */
    private static String resolveJavaExecutable() throws IOException {
        var osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        var execName = osName.contains("win") ? "java.exe" : "java";

        var javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            var candidate = Path.of(javaHome, "bin", execName);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }

        var pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (var dir : Splitter.on(File.pathSeparatorChar).split(pathEnv)) {
                if (dir.isBlank()) continue;
                var candidate = Path.of(dir, execName);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }

        throw new IOException(
                "Unable to locate a Java executable; set JAVA_HOME or ensure 'java' is on the system PATH.");
    }
}
