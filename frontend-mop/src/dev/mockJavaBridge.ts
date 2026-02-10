/**
 * Mock Java bridge for development mode
 * Simulates the Java backend functionality for symbol lookups and interactions
 */


export interface MockJavaBridge {
    onAck: (epoch: number) => void;
    searchStateChanged: (total: number, current: number) => void;
    lookupSymbolsAsync: (symbolNamesJson: string, seq: number, contextId: string) => void;
    lookupFilePathsAsync: (filePathsJson: string, seq: number, contextId: string) => void;
    jsLog: (level: string, message: string) => void;
    onSymbolClick: (symbolName: string, symbolExists: boolean, symbolFqn: string | null, x: number, y: number) => void;
    onFilePathClick: (filePath: string, exists: boolean, matchesJson: string, x: number, y: number) => void;
    onZoomChanged: (zoom: number) => void;
    _mockSymbolsSet: Set<string>;
    _mockFilePathsSet: Set<string>;
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

    // Mock file paths for testing - these represent files that "exist" in the mock project
    const mockFilePathsSet = new Set([
        // Project root files
        'package.json', 'README.md', 'index.html', 'dev.html',
        'vite.config.mjs', 'tsconfig.json', 'tsconfig.node.json',
        'build.gradle', 'pom.xml',
        // Frontend structure
        'frontend-mop/package.json', 'frontend-mop/index.html', 'frontend-mop/dev.html',
        'frontend-mop/vite.config.mjs', 'frontend-mop/tsconfig.json',
        'frontend-mop/src/index.ts', 'frontend-mop/src/App.svelte',
        'frontend-mop/src/components/Button.tsx', 'frontend-mop/src/lib/utils.ts',
        'frontend-mop/src/stores/symbolCacheStore.ts', 'frontend-mop/src/stores/filePathCacheStore.ts',
        'frontend-mop/src/dev/mockJavaBridge.ts', 'frontend-mop/src/lib/filePathDetection.ts',
        // Java source structure
        'src/main/App.java', 'src/main/java/Main.java', 'src/main/java/utils/Helper.java',
        'src/test/java/AppTest.java', 'src/test/java/utils/HelperTest.java',
        // Common test files
        'tests/unit/parser.test.js', 'tests/integration/api.test.ts',
        'test/index.test.js', 'spec/components/button.spec.ts',
        // Python files
        'main.py', 'src/app.py', 'tests/test_main.py', 'requirements.txt',
        // Configuration files
        '.gitignore', '.eslintrc.js', 'jest.config.js', 'webpack.config.js'
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

        onZoomChanged: function(zoom: number) {
            console.log(`[Mock JavaBridge] Zoom changed to: ${zoom}`);
            // Store the zoom value in localStorage for persistence
            try {
                localStorage.setItem('brokk.zoom', String(zoom));
            } catch (e) {
                // ignore localStorage errors
            }
        },

        _mockSymbolsSet: mockSymbolsSet,
        _mockFilePathsSet: mockFilePathsSet,

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

        // File path lookup implementation for new file path detection feature
        lookupFilePathsAsync: function(filePathsJson: string, seq: number, contextId: string) {
            console.log(`[Mock JavaBridge] File path lookup request: filePaths=${filePathsJson}, seq=${seq}, contextId=${contextId}`);
            const results: Record<string, any> = {};

            // Parse file paths from JSON
            const filePaths = JSON.parse(filePathsJson);
            console.log(`[Mock JavaBridge] Processing ${filePaths.length} file paths:`, filePaths);

            for (const filePath of filePaths) {
                // Parse line numbers from file path (e.g., "App.java:25" or "package.json:15-20")
                const lineNumberMatch = filePath.match(/^(.+?):(\d+)(?:-(\d+))?$/);
                let cleanPath = filePath;
                let lineNumber = undefined;
                let lineRange = undefined;

                if (lineNumberMatch) {
                    cleanPath = lineNumberMatch[1];
                    const startLine = parseInt(lineNumberMatch[2], 10);
                    if (lineNumberMatch[3]) {
                        const endLine = parseInt(lineNumberMatch[3], 10);
                        lineRange = [startLine, endLine];
                    } else {
                        lineNumber = startLine;
                    }
                }

                // Check if file path exists in our mock set
                const exists = mockFilePathsSet.has(cleanPath);

                if (exists) {
                    results[filePath] = {
                        exists: true,
                        matches: [{
                            relativePath: cleanPath,
                            absolutePath: `/mock/project/root/${cleanPath}`,
                            isDirectory: false,
                            lineNumber: lineNumber,
                            lineRange: lineRange
                        }],
                        confidence: 100,
                        processingTimeMs: Math.floor(Math.random() * 20) + 5 // Random 5-25ms
                    };
                    console.log(`[Mock JavaBridge] File path found: ${filePath} -> ${cleanPath}`);
                } else {
                    results[filePath] = {
                        exists: false,
                        matches: [],
                        confidence: 0,
                        processingTimeMs: Math.floor(Math.random() * 10) + 2 // Random 2-12ms
                    };
                    console.log(`[Mock JavaBridge] File path not found: ${filePath}`);
                }
            }

            console.log(`[Mock JavaBridge] File path results:`, results);

            // Simulate async behavior, then call the response handler
            setTimeout(() => {
                if (window.brokk?.onFilePathLookupResponse) {
                    window.brokk.onFilePathLookupResponse(results, seq, contextId);
                    console.log(`[Mock JavaBridge] File path response sent with seq=${seq}, contextId=${contextId}`);
                } else {
                    console.error('[Mock JavaBridge] File path response handler not available');
                }
            }, 30); // 30ms delay to simulate file system lookup
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
        },

        // Mock file path click implementation
        onFilePathClick: function(filePath: string, exists: boolean, matchesJson: string, x: number, y: number) {
            console.log(`[Mock JavaBridge] Click on file path: ${filePath}`);

            try {
                const matches = JSON.parse(matchesJson);
                console.log(`[Mock JavaBridge] File path matches:`, matches);

                // Simulate context menu with file-specific actions
                const actions = [];
                if (exists && matches.length > 0) {
                    actions.push('Open File');
                    actions.push('Open in Editor');
                    actions.push('Show in File Tree');
                    if (matches[0].lineNumber) {
                        actions.push(`Go to Line ${matches[0].lineNumber}`);
                    }
                    if (matches[0].lineRange) {
                        actions.push(`Go to Lines ${matches[0].lineRange[0]}-${matches[0].lineRange[1]}`);
                    }
                }
                actions.push('Copy File Path');

                const fileInfo = exists && matches.length > 0
                    ? `\nPath: ${matches[0].relativePath}\nAbsolute: ${matches[0].absolutePath}`
                    : '';

                const selectedAction = confirm(`File Path: ${filePath} (${exists ? 'exists' : 'does not exist'})${fileInfo}\n\nAvailable actions:\n${actions.map((action, i) => `${i + 1}. ${action}`).join('\n')}\n\nClick OK to simulate opening context menu, Cancel to ignore.`);

                if (selectedAction) {
                    console.log(`[Mock JavaBridge] File path context menu opened`);
                }
            } catch (error) {
                console.error(`[Mock JavaBridge] Error parsing matches JSON:`, error);
            }
        }
    };
}
