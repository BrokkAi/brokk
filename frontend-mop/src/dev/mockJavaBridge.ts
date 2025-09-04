/**
 * Mock Java bridge for development mode
 * Simulates the Java backend functionality for symbol lookups and interactions
 */

export interface MockJavaBridge {
    onAck: (epoch: number) => void;
    searchStateChanged: (total: number, current: number) => void;
    lookupSymbolsAsync: (symbolNamesJson: string, seq: number, contextId: string) => void;
    jsLog: (level: string, message: string) => void;
    onSymbolClick: (symbolName: string, symbolExists: boolean, symbolFqn: string | null, x: number, y: number) => void;
    _mockSymbolsSet: Set<string>;
}

/**
 * Create and initialize the mock Java bridge
 */
export function createMockJavaBridge(): MockJavaBridge {
    // Mock symbols for testing - shared across functions
    const mockSymbolsSet = new Set([
        // Java symbols
        'String', 'List', 'Map', 'ArrayList', 'HashMap', 'Set', 'Collection',
        'StringBuilder', 'StringBuffer', 'Integer', 'Long', 'Double', 'Float', 'Boolean',
        'toString', 'size', 'add', 'get', 'put', 'remove', 'contains', 'isEmpty',
        'Object', 'Class', 'Exception', 'Thread', 'Runnable',
        'System', 'Math', 'File', 'Path', 'Files', 'Scanner', 'Properties',
        'Random', 'UUID', 'Pattern', 'Matcher', 'BigDecimal', 'BigInteger',
        // Swing utility classes for complex method testing
        'SwingUtil', 'SwingUtilities', 'EventQueue', 'JOptionPane',
        // Python symbols
        'list', 'dict', 'str', 'int', 'float', 'bool', 'len', 'range',
        'append', 'keys', 'values', 'items', 'pop', 'extend', 'join',
        'print', 'input', 'open', 'close', 'read', 'write',
        // JavaScript/TypeScript symbols
        'var', 'let', 'const', 'function', 'class', 'interface', 'type',
        'Array', 'Object', 'Promise', 'async', 'await', 'console',
        'document', 'window', 'setTimeout', 'setInterval',
        // Generic programming terms
        'parser', 'lexer', 'token', 'node', 'tree', 'visitor',
        'handler', 'callback', 'iterator', 'stream', 'buffer'
    ]);

    return {
        onAck: (epoch: number) => {
            // No-op for development
        },

        searchStateChanged: (total: number, current: number) => {
            console.log(`[Java Bridge Mock] Search State: total=${total}, current=${current}`);
            const stateDisplay = document.getElementById('search-state-display');
            if (stateDisplay) {
                stateDisplay.textContent = `Total: ${total}, Current: ${current}`;
            }
        },

        _mockSymbolsSet: mockSymbolsSet,

        // Unified symbol lookup implementation for new store-based architecture
        lookupSymbolsAsync: function(symbolNamesJson: string, seq: number, contextId: string) {
            console.log(`[Mock JavaBridge] Lookup request received: symbols=${symbolNamesJson}, seq=${seq}, contextId=${contextId}`);
            const results: Record<string, any> = {};

            // Parse symbols from JSON
            const symbols = JSON.parse(symbolNamesJson);
            console.log(`[Mock JavaBridge] Processing ${symbols.length} symbols:`, symbols);

            for (const symbol of symbols) {
                // First check for exact matches in our mock symbol set
                const exactExists = mockSymbolsSet.has(symbol);
                if (exactExists) {
                    results[symbol] = {
                        fqn: `com.example.mock.${symbol}`,
                        highlightRanges: [{start: 0, end: symbol.length}],
                        isPartialMatch: false,
                        originalText: symbol
                    };
                    continue;
                }

                // Then check for partial matches (e.g., List.add -> List, SwingUtil.runOnEdt(...) -> SwingUtil)
                // Java method reference pattern: ClassName.method or ClassName.method(...)
                const javaMethodMatch = /^([A-Z][a-zA-Z0-9_]*(?:\.[A-Z][a-zA-Z0-9_]*)*?)\.([a-z_][a-zA-Z0-9_]*)(?:\([^)]*\))?$/.exec(symbol);
                if (javaMethodMatch) {
                    const className = javaMethodMatch[1];
                    const methodName = javaMethodMatch[2];

                    // Check if the extracted class exists in our mock set
                    if (mockSymbolsSet.has(className)) {
                        results[symbol] = {
                            fqn: `com.example.mock.${className}`,
                            highlightRanges: [{start: 0, end: className.length}], // Highlight only the class part
                            isPartialMatch: true,
                            originalText: symbol
                        };
                        console.log(`[Mock JavaBridge] Partial match: ${symbol} -> ${className}`);
                        continue;
                    }
                }

                // No match found - results will not include this symbol
                console.log(`[Mock JavaBridge] No match for: ${symbol}`);
            }

            console.log(`[Mock JavaBridge] Results:`, results);

            // Simulate async behavior, then call the response handler
            setTimeout(() => {
                if (window.brokk?.onSymbolLookupResponse) {
                    // Always use the new signature with sequence and contextId
                    window.brokk.onSymbolLookupResponse(results, seq, contextId);
                    console.log(`[Mock JavaBridge] Response sent with seq=${seq}, contextId=${contextId}`);
                } else {
                    console.error('[Mock JavaBridge] Response handler not available');
                }
            }, 50); // 50ms delay to simulate async behavior
        },

        // Helper for debugging
        jsLog: function(level: string, message: string) {
            console.log(`[Mock JavaBridge ${level}] ${message}`);
        },

        // Mock symbol click implementation
        onSymbolClick: function(symbolName: string, symbolExists: boolean, symbolFqn: string | null, x: number, y: number) {
            console.log(`[Mock JavaBridge] Click on ${symbolName}`);

            // Simulate context menu with basic actions
            const actions = [];
            if (symbolExists) {
                actions.push('Open in Preview');
                actions.push('Open in Project Tree');
            }
            actions.push('Copy Symbol Name');

            const selectedAction = confirm(`Symbol: ${symbolName} (${symbolExists ? 'exists' : 'does not exist'})\nFQN: ${symbolFqn || 'N/A'}\n\nAvailable actions:\n${actions.map((action, i) => `${i + 1}. ${action}`).join('\n')}\n\nClick OK to simulate opening context menu, Cancel to ignore.`);

            if (selectedAction) {
                console.log(`[Mock JavaBridge] Context menu opened`);
            }
        }
    };
}