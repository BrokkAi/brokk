import type {Root} from 'hast';
import {visit} from 'unist-util-visit';

/**
 * Rehype plugin for symbol lookup integration.
 * This plugin processes inlineCode elements during the unified transformation pipeline,
 * marking them with symbol information for later enhancement.
 */

// Symbol validation utilities (moved from processor.ts)
function cleanSymbolName(raw: string): string {
    // Remove parentheses, brackets, and other non-identifier characters but keep dots for FQN
    return raw.replace(/[()[\]{}<>]/g, '').trim();
}

function isValidSymbolName(name: string): boolean {
    if (!name || name.length === 0) {
        return false;
    }

    // Support both simple identifiers and qualified names (with dots)
    // Each segment should be a valid identifier
    const segments = name.split('.');
    const identifierPattern = /^[a-zA-Z_][a-zA-Z0-9_]*$/;

    // All segments must be valid identifiers
    const allSegmentsValid = segments.every(segment =>
        segment.length > 0 && identifierPattern.test(segment)
    );

    const isLongEnough = name.length > 1;

    return allSegmentsValid && isLongEnough;
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

        // Visit all inlineCode elements in the HAST (HTML AST)
        visit(tree, 'element', (node: any) => {
            if (node.tagName === 'code' && node.children && node.children.length > 0) {
                const textNode = node.children[0];
                if (textNode && textNode.type === 'text' && textNode.value) {
                    const cleaned = cleanSymbolName(textNode.value);

                    if (isValidSymbolName(cleaned)) {
                        symbols.add(cleaned);

                        // Mark the node for potential symbol enhancement
                        if (!node.properties) node.properties = {};
                        node.properties['data-symbol-candidate'] = cleaned;
                    }
                }
            }
        });

        // Store symbols in tree data for post-processing
        if (symbols.size > 0) {
            tree.data = tree.data || {};
            (tree.data as any).symbolCandidates = symbols;
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
export function enhanceSymbolCandidates(tree: Root, symbolResults: Record<string, {exists: boolean}>): void {
    let candidatesFound = 0;
    let symbolsEnhanced = 0;

    visit(tree, 'element', (node: any) => {
        const symbolCandidate = node.properties?.['data-symbol-candidate'];
        if (symbolCandidate) {
            candidatesFound++;
            if (symbolResults[symbolCandidate]) {
                const result = symbolResults[symbolCandidate];
                workerLog('info', `[ENHANCE] Processing symbol: ${symbolCandidate}, exists: ${result.exists}`);

                if (result.exists) {
                    symbolsEnhanced++;
                    // Add CSS class for symbols that exist
                    if (!node.properties.className) node.properties.className = [];
                    node.properties.className.push('symbol-exists');

                    // Add data attributes for click handling
                    node.properties['data-symbol'] = symbolCandidate;
                    node.properties['data-symbol-exists'] = 'true';

                    workerLog('info', `[ENHANCE] Enhanced symbol: ${symbolCandidate}, className: ${node.properties.className}`);
                    workerLog('info', `[ENHANCE] Full node after enhancement: ${JSON.stringify({
                        tagName: node.tagName,
                        properties: node.properties,
                        children: node.children
                    })}`);
                }

                // Clean up the candidate marker
                delete node.properties['data-symbol-candidate'];
            }
        }
    });

    workerLog('info', `[ENHANCE] Found ${candidatesFound} candidates, enhanced ${symbolsEnhanced} symbols`);
}