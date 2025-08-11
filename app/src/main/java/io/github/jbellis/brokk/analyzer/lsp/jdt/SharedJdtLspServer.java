package io.github.jbellis.brokk.analyzer.lsp.jdt;

import io.github.jbellis.brokk.analyzer.lsp.LspFileUtilities;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import io.github.jbellis.brokk.analyzer.lsp.SupportedLspServer;
import org.eclipse.lsp4j.services.LanguageClient;

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

    private SharedJdtLspServer() {
        super(SupportedLspServer.JDT);
    }

    public static SharedJdtLspServer getInstance() {
        return INSTANCE;
    }


    @Override
    protected ProcessBuilder createProcessBuilder(Path cache) throws IOException {
        final Path serverHome = LspFileUtilities.unpackLspServer("jdt");
        final Path launcherJar = LspFileUtilities.findFile(serverHome, "org.eclipse.equinox.launcher_");
        final Path configDir = LspFileUtilities.findConfigDir(serverHome);
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
                "-Xmx2g",
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
    protected LanguageClient createLanguageClient(
            String language,
            CountDownLatch serverReadyLatch,
            Map<String, CountDownLatch> workspaceReadyLatchMap
    ) {
        return new JdtLanguageClient(language, serverReadyLatch, workspaceReadyLatchMap);
    }
}