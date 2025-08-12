package io.github.jbellis.brokk.analyzer.lsp.jdt;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.lsp.LspFileUtilities;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import io.github.jbellis.brokk.analyzer.lsp.SupportedLspServer;
import io.github.jbellis.brokk.gui.dialogs.analyzer.JavaAnalyzerSettingsPanel;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Manages a single, shared instance of the JDT Language Server process.
 * This class is a thread-safe singleton.
 */
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
     * Returns the singleton instance with the given {@link IConsoleIO} instance as the IO to use for future diagostics.
     *
     * @param io the IO instance to use. If `null`, user-facing diagnostics will be disabled.
     * @return the singleton LSP client instance.
     */
    public static SharedJdtLspServer getInstance(@Nullable IConsoleIO io) {
        if (INSTANCE.languageClient != null) {
            INSTANCE.languageClient.setIo(io);
        }
        return INSTANCE;
    }

    @Override
    protected ProcessBuilder createProcessBuilder(Path cache) throws IOException {
        final Path serverHome = LspFileUtilities.unpackLspServer("jdt");
        final Path launcherJar = LspFileUtilities.findFile(serverHome, "org.eclipse.equinox.launcher_");
        final Path configDir = LspFileUtilities.findConfigDir(serverHome);
        final int memoryMB = JavaAnalyzerSettingsPanel.getSavedMemoryValueMb();
        logger.debug("Creating JDT LSP process with a max heap size of {} Mb", memoryMB);

        return new ProcessBuilder(
                "java",
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
                "-jar", launcherJar.toString(),
                "-configuration", configDir.toString(),
                "-data", cache.toString()
        );
    }

    @Override
    protected LanguageClient getLanguageClient(
            String language,
            CountDownLatch serverReadyLatch,
            Map<String, CountDownLatch> workspaceReadyLatchMap
    ) {
        this.languageClient = new JdtLanguageClient(language, serverReadyLatch, workspaceReadyLatchMap);
        return this.languageClient;
    }
}
