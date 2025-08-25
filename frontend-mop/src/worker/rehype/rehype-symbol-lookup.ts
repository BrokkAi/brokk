import type {Root, Parent} from 'hast';
import {visit} from 'unist-util-visit';

/**
 * Rehype plugin for symbol lookup integration.
 * This plugin processes inlineCode elements during the unified transformation pipeline,
 * storing both symbols and node references for later enhancement.
 * adds attributes to the dom for identificationre
 *
 */

// Java class name validation - only accept valid Java class names (simple or fully qualified)
function cleanSymbolName(raw: string): string {
    const trimmed = raw.trim();

    // Length check
    if (trimmed.length < 2 || trimmed.length > 200) {
        return '';
    }

    // Check if it could be a Java class name (simple or qualified)
    if (isValidJavaClassName(trimmed)) {
        return trimmed;
    }

    return '';
}

function isValidJavaClassName(name: string): boolean {
    // Split by dots for package.Class format
    const segments = name.split('.');

    // Each segment must be a valid Java identifier
    for (const segment of segments) {
        if (!isValidJavaIdentifier(segment)) {
            return false;
        }
    }

    // If multiple segments, last one should look like a class name (start with uppercase)
    if (segments.length > 1) {
        const className = segments[segments.length - 1];
        return /^[A-Z]/.test(className);
    }

    // Single segment should start with uppercase (class name)
    return /^[A-Z]/.test(name);
}

function isValidJavaIdentifier(identifier: string): boolean {
    // Java identifier: start with letter/underscore/dollar, then letters/digits/underscore/dollar
    return /^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(identifier);
}

// Store symbol nodes during traversal for efficient enhancement
interface SymbolNode {
    node: any;
    symbol: string;
}

/**
 * Extract symbols from the AST and store both symbols and node references.
 * This approach allows us to avoid a second traversal for enhancement.
 */
export function rehypeSymbolLookup() {
    return (tree: Root) => {
        // Note: We're in a Web Worker context, so no direct JavaBridge access
        const symbols = new Set<string>();
        const symbolNodes: SymbolNode[] = [];
        let totalCodeElements = 0;
        let validSymbols = 0;

        // Single traversal: extract all inline code symbols AND store node references
        visit(tree, 'element', (node: any, index: number | undefined, parent: Parent | undefined) => {
            // Only process <code> elements that are NOT inside <pre> (i.e., inline code, not code fences)
            if (node.tagName === 'code' && parent?.tagName !== 'pre' && node.children && node.children.length > 0) {
                totalCodeElements++;
                const textNode = node.children[0];
                if (textNode && textNode.type === 'text' && textNode.value) {
                    const rawValue = textNode.value;
                    const cleaned = cleanSymbolName(rawValue);

                    if (cleaned) {
                        validSymbols++;
                        symbols.add(cleaned);

                        // Store node reference for efficient enhancement later
                        symbolNodes.push({ node, symbol: cleaned });
                    }
                }
            }
        });

        // Store both symbols and node references for post-processing
        if (symbols.size > 0) {
            tree.data = tree.data || {};
            (tree.data as any).symbolCandidates = symbols;
            (tree.data as any).symbolNodes = symbolNodes;
        }
    };
}

/**
 * Post-processing function to enhance symbol candidates with lookup results.
 * Uses pre-stored node references to avoid second tree traversal - 50% performance improvement.
 * Uses optimized format with only known symbols (symbol -> fqn mapping).
 */
export function enhanceSymbolCandidates(tree: Root, symbolResults: Record<string, string>): void {
    const symbolNodes = (tree.data as any)?.symbolNodes as SymbolNode[];
    if (!symbolNodes) {
        return; // No symbols to enhance
    }

    let candidatesFound = 0;
    let symbolsEnhanced = 0;

    // Direct node enhancement - no tree traversal needed!
    for (const { node, symbol } of symbolNodes) {
        candidatesFound++;

        // Check if symbol exists in the results (optimized format: only known symbols are included)
        const fqn = symbolResults[symbol];
        if (fqn) {
            symbolsEnhanced++;
            // Ensure node has properties object
            if (!node.properties) node.properties = {};

            // Add CSS class for symbols that exist
            if (!node.properties.className) node.properties.className = [];
            node.properties.className.push('symbol-exists');

            // Also set as 'class' property for proper HTML rendering
            node.properties.class = node.properties.className;

            // Add data attributes for click handling
            node.properties['data-symbol'] = symbol;
            node.properties['data-symbol-exists'] = 'true';
            // Store FQN from optimized format
            node.properties['data-symbol-fqn'] = fqn;
        }
    }

    // Clean up the stored node references to free memory
    delete (tree.data as any).symbolNodes;
}
