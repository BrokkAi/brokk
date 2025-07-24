package io.github.jbellis.brokk.analyzer.lsp;

/**
 * Manages a single, shared instance of the JDT Language Server process.
 * This class is a thread-safe singleton.
 */
public final class SharedLspServer extends LspServer {

    private static final SharedLspServer INSTANCE = new SharedLspServer();

    private SharedLspServer() {
    }

    public static SharedLspServer getInstance() {
        return INSTANCE;
    }
    
}