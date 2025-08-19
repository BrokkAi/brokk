import type {Root} from 'hast';
import {visit} from 'unist-util-visit';

/**
 * Rehype plugin for symbol lookup integration.
 * This plugin processes inlineCode elements during the unified transformation pipeline,
 * marking them with symbol information for later enhancement.
 */

// Symbol validation utilities (moved from processor.ts)
function cleanSymbolName(raw: string): string {
    // Remove parentheses, brackets, and other non-identifier characters
    return raw.replace(/[()[\]{}<>]/g, '').trim();
}

function isValidSymbolName(name: string): boolean {
    if (!name || name.length === 0) {
        return false;
    }

    // Basic check for valid identifier pattern (Java/Python/etc style)
    // Starts with letter or underscore, followed by letters, numbers, underscores
    const identifierPattern = /^[a-zA-Z_][a-zA-Z0-9_]*$/;
    const matchesPattern = identifierPattern.test(name);
    const isLongEnough = name.length > 1;

    return matchesPattern && isLongEnough; // Avoid single letters
}

/**
 * Extract symbols from the AST and store them in tree data for later processing.
 * This approach allows us to work within the rehype plugin system while deferring
 * the actual symbol lookup (which requires JavaBridge access) to post-processing.
 */
export function rehypeSymbolLookup() {
    return (tree: Root) => {
        // Note: We're in a Web Worker context, so no direct JavaBridge access
        // Debug output will come from the main thread when symbols are processed
        const symbols = new Set<string>();
        let totalCodeElements = 0;
        let validSymbols = 0;

        workerLog('info', '[SYMBOL-DETECT] Starting symbol detection...');

        // Visit all inlineCode elements in the HAST (HTML AST)
        visit(tree, 'element', (node: any) => {
            if (node.tagName === 'code' && node.children && node.children.length > 0) {
                totalCodeElements++;
                const textNode = node.children[0];
                if (textNode && textNode.type === 'text' && textNode.value) {
                    const rawValue = textNode.value;
                    const cleaned = cleanSymbolName(rawValue);

                    workerLog('info', `[SYMBOL-DETECT] Found code element: "${rawValue}" -> cleaned: "${cleaned}"`);

                    if (isValidSymbolName(cleaned)) {
                        validSymbols++;
                        symbols.add(cleaned);

                        // Mark the node for potential symbol enhancement
                        if (!node.properties) node.properties = {};
                        node.properties['data-symbol-candidate'] = cleaned;

                        workerLog('info', `[SYMBOL-DETECT] Valid symbol candidate: "${cleaned}"`);
                    } else {
                        workerLog('info', `[SYMBOL-DETECT] Invalid symbol (too short or bad pattern): "${cleaned}"`);
                    }
                }
            }
        });

        workerLog('info', `[SYMBOL-DETECT] Detection complete: ${totalCodeElements} code elements, ${validSymbols} valid symbols, ${symbols.size} unique symbols`);
        if (symbols.size > 0) {
            workerLog('info', `[SYMBOL-DETECT] Symbol candidates: ${Array.from(symbols).join(', ')}`);
        }

        // Store symbols in tree data for post-processing
        if (symbols.size > 0) {
            tree.data = tree.data || {};
            (tree.data as any).symbolCandidates = symbols;
            workerLog('info', `[SYMBOL-DETECT] Stored ${symbols.size} symbol candidates in tree data`);
        } else {
            workerLog('info', '[SYMBOL-DETECT] No symbol candidates found');
        }
    };
}

// Worker logging helper
function workerLog(level: 'info' | 'warn' | 'error' | 'debug', message: string) {
    self.postMessage({ type: 'worker-log', level, message });
}

/**
 * Post-processing function to enhance symbol candidates with lookup results.
 * This runs after the rehype plugin and can access the tree with marked symbols.
 */
export function enhanceSymbolCandidates(tree: Root, symbolResults: Record<string, {exists: boolean, fqn?: string | null}>): void {
    let candidatesFound = 0;
    let symbolsEnhanced = 0;

    workerLog('info', `[ENHANCE] Starting enhancement with ${Object.keys(symbolResults).length} symbol results`);
    workerLog('info', `[ENHANCE] Symbol results: ${JSON.stringify(symbolResults)}`);

    visit(tree, 'element', (node: any) => {
        const symbolCandidate = node.properties?.['data-symbol-candidate'];
        if (symbolCandidate) {
            candidatesFound++;
            workerLog('info', `[ENHANCE] Found candidate: ${symbolCandidate}`);

            if (symbolResults[symbolCandidate]) {
                const result = symbolResults[symbolCandidate];
                workerLog('info', `[ENHANCE] Processing symbol: ${symbolCandidate}, exists: ${result.exists}, fqn: ${result.fqn || 'null'}`);

                if (result.exists) {
                    symbolsEnhanced++;
                    // Add CSS class for symbols that exist
                    if (!node.properties.className) node.properties.className = [];
                    node.properties.className.push('symbol-exists');

                    // Also set as 'class' property for proper HTML rendering
                    node.properties.class = node.properties.className;

                    // Add data attributes for click handling
                    node.properties['data-symbol'] = symbolCandidate;
                    node.properties['data-symbol-exists'] = 'true';
                    // Store FQN if available
                    if (result.fqn) {
                        node.properties['data-symbol-fqn'] = result.fqn;
                    }

                    workerLog('info', `[ENHANCE] Enhanced symbol: ${symbolCandidate}, className: ${node.properties.className}`);
                    workerLog('info', `[ENHANCE] Node properties after enhancement: ${JSON.stringify(node.properties)}`);
                } else {
                    workerLog('info', `[ENHANCE] Symbol ${symbolCandidate} does not exist`);
                }

                // Clean up the candidate marker
                delete node.properties['data-symbol-candidate'];
            } else {
                workerLog('warn', `[ENHANCE] No result found for candidate: ${symbolCandidate}`);
            }
        }
    });

    workerLog('info', `[ENHANCE] Enhancement complete: Found ${candidatesFound} candidates, enhanced ${symbolsEnhanced} symbols`);
}