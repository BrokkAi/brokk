import type {Root} from 'hast';
import {visit} from 'unist-util-visit';
import {buildUnifiedDiff} from './diff-utils';
import type {EditBlockProperties} from './types';

/**
 * Simplified rehype-edit-diff for VSCode extension.
 * Computes diff metrics and sets expansion flag.
 * Actual rendering is handled by rehype-vscode-render.
 */
export function rehypeEditDiffSimple() {
    return function (tree: Root) {
        visit(tree, (n: any) => n.tagName === 'edit-block', (node: any) => {
            const p: EditBlockProperties = node.properties;
            // Add an empty text node so the element is not self-closing
            node.children = [{type: 'text', value: ''}];
            if (!p.headerOk) return;

            // Trim leading/trailing newlines from search/replace
            const search = (p.search ?? '').replace(/^\n+|\n+$/g, '');
            const replace = (p.replace ?? '').replace(/^\n+|\n+$/g, '');

            // Compute diff and store results for downstream rendering
            const {text, added, removed} = buildUnifiedDiff(search, replace);
            p.adds = added.length;
            p.dels = removed.length;
            p.diffText = text;
            p.diffAdded = added;
            p.diffRemoved = removed;

            const totalChanges = (p.adds ?? 0) + (p.dels ?? 0);
            const AUTO_EXPAND_MAX = 50;

            // Auto-expand when complete and small enough
            if (
                (p.complete === true) &&
                totalChanges > 0 &&
                totalChanges <= AUTO_EXPAND_MAX
            ) {
                p.isExpanded = true;
            }
        });
    };
}
