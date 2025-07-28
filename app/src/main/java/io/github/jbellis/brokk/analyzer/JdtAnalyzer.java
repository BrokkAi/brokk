package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.analyzer.lsp.LspAnalyzer;
import io.github.jbellis.brokk.analyzer.lsp.LspServer;
import io.github.jbellis.brokk.analyzer.lsp.SharedLspServer;
import io.github.jbellis.brokk.analyzer.lsp.jdt.JdtProjectHelper;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public class JdtAnalyzer implements LspAnalyzer {

    @NotNull
    private final Path projectRoot;
    @NotNull
    private final String workspace;
    @NotNull
    private final SharedLspServer sharedServer;

    /**
     * Creates an analyzer for a specific project workspace.
     *
     * @param projectPath   The path to the Java project to be analyzed.
     * @param excludedPaths A set of glob patterns to exclude from analysis (e.g., "build", "**\/target").
     * @throws IOException if the server cannot be started.
     */
    public JdtAnalyzer(Path projectPath, Set<String> excludedPaths) throws IOException {
        this.projectRoot = projectPath.toAbsolutePath().normalize();
        if (!this.projectRoot.toFile().exists()) {
            throw new FileNotFoundException("Project directory does not exist: " + projectRoot);
        } else {
            JdtProjectHelper.ensureProjectConfiguration(this.projectRoot);
            this.sharedServer = SharedLspServer.getInstance();
            this.sharedServer.registerClient(this.projectRoot, excludedPaths);
            this.sharedServer.refreshWorkspace().join();
            this.workspace = this.projectRoot.toUri().toString();
        }
    }

    @Override
    public @NotNull Path getProjectRoot() {
        return this.projectRoot;
    }

    @Override
    public @NotNull String getWorkspace() {
        return this.workspace;
    }

    @Override
    public @NotNull LspServer getServer() {
        return this.sharedServer;
    }

    /**
     * Strips the method signature (parentheses and parameters) from a method name string.
     *
     * @param methodName The full method name from the symbol object (e.g., "myMethod(int)").
     * @return The clean method name without the signature (e.g., "myMethod").
     */
    @Override
    @NotNull
    public String resolveMethodName(@NotNull String methodName) {
        final var cleanedName = methodName.replace('$', '.');
        int parenIndex = cleanedName.indexOf('(');

        // If a parenthesis is found, return the part of the string before it.
        if (parenIndex != -1) {
            return cleanedName.substring(0, parenIndex);
        }

        // Otherwise, return the original string.
        return cleanedName;
    }


    @Override
    public void close() {
        sharedServer.unregisterClient(this.projectRoot);
    }
}