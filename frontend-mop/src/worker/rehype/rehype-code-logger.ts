import type {Root} from 'hast';
import {visit} from 'unist-util-visit';

/**
 * Rehype plugin for logging all code elements before symbol processing.
 * This runs before rehype-symbol-lookup to capture the raw state of code elements.
 */
export function rehypeCodeLogger() {
    workerLog('info', '[CODE-LOGGER] Plugin initialized and ready to process trees');
    return (tree: Root) => {
        let totalCodeElements = 0;
        let totalInlineCodeElements = 0;

        workerLog('info', '[CODE-LOGGER] Starting code element logging...');

        // Visit all elements and log code-related ones
        visit(tree, 'element', (node: any) => {
            if (node.tagName === 'code') {
                totalCodeElements++;

                // Log detailed information about each code element
                const hasChildren = node.children && node.children.length > 0;
                const textContent = hasChildren && node.children[0]?.type === 'text'
                    ? node.children[0].value
                    : null;

                // Determine if this is inline code (not in pre) or block code (in pre)
                const isInlineCode = !node.properties?.className?.includes('language-');
                if (isInlineCode) {
                    totalInlineCodeElements++;
                }

                workerLog('info', `[CODE-LOGGER] ${isInlineCode ? 'Inline' : 'Block'} code element #${totalCodeElements}:`);
                // workerLog('info', `[CODE-LOGGER]  - tagName: "${node.tagName}"`);
                // workerLog('info', `[CODE-LOGGER]  - children: ${node.children?.length || 0}`);
                workerLog('info', `[CODE-LOGGER]  - textContent: "${textContent || 'null'}"`);
                // workerLog('info', `[CODE-LOGGER]  - properties: ${JSON.stringify(node.properties || {})}`);

                if (textContent) {
                    workerLog('info', `  - raw text: "${textContent}"`);
                } else if (hasChildren) {
                    workerLog('info', `  - children types: [${node.children.map((c: any) => c.type).join(', ')}]`);
                } else {
                    workerLog('info', `  - no children or text content`);
                }

                workerLog('info', `  ---`);
            }
        });

        workerLog('info', `[CODE-LOGGER] Summary: Found ${totalCodeElements} total code elements (${totalInlineCodeElements} inline, ${totalCodeElements - totalInlineCodeElements} block)`);
        workerLog('info', '[CODE-LOGGER] Tree processing completed');

        // CRITICAL: Must return the tree for the next plugin in the chain
        return tree;
    };
}

// Worker logging helper
function workerLog(level: 'info' | 'warn' | 'error' | 'debug', message: string) {
    self.postMessage({ type: 'worker-log', level, message });
}
