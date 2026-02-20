import type {Root} from 'hast';
import type {HighlighterCore} from 'shiki/core';
import {visit} from 'unist-util-visit';
import {buildUnifiedDiff, getMdLanguageTag} from './diff-utils';
import type {EditBlockProperties} from './types';
import {transformerDiffLines} from './shiki-diff-transformer';

/**
 * Walk the Shiki HAST and remove context lines (`.line` without `.diff-line`)
 * plus any whitespace-only text nodes between lines. This eliminates gaps
 * without relying on CSS hacks (font-size:0, flexbox) that can cause stalls.
 */
function stripContextLines(hast: any) {
    visit(hast, (n: any) => n.tagName === 'code', (codeNode: any) => {
        if (!Array.isArray(codeNode.children)) return;
        codeNode.children = codeNode.children.filter((child: any) => {
            if (child.type === 'element' && child.tagName === 'span') {
                // Shiki uses properties.class (not className)
                const classes = child.properties?.class ?? child.properties?.className ?? [];
                const classArr = Array.isArray(classes) ? classes : [];
                if (classArr.includes('diff-line')) {
                    return true; // Keep diff lines
                }
                if (classArr.includes('line')) {
                    return false; // Strip context lines
                }
            }
            // Strip whitespace-only text nodes (newlines between spans)
            if (child.type === 'text' && /^\s*$/.test(child.value)) {
                return false;
            }
            // Keep everything else
            return true;
        });
    });
}

/**
 * Simplified rehype-edit-diff for VSCode extension.
 * No worker expand/collapse state — always auto-expand if ≤50 total changes
 * and block is structurally complete.
 */
export function rehypeEditDiffSimple(highlighter: HighlighterCore | null) {
    return function (tree: Root) {
        visit(tree, (n: any) => n.tagName === 'edit-block', (node: any) => {
            const p: EditBlockProperties = node.properties;
            // Add an empty text node so the element is not self-closing
            node.children = [{type: 'text', value: ''}];
            if (!p.headerOk) return;

            // Trim leading/trailing newlines from search/replace — the micromark
            // tokenizer captures the newline after the header/divider markers.
            const search = (p.search ?? '').replace(/^\n+|\n+$/g, '');
            const replace = (p.replace ?? '').replace(/^\n+|\n+$/g, '');

            // Compute diff metrics
            const {text, added, removed} = buildUnifiedDiff(search, replace);
            p.adds = added.length;
            p.dels = removed.length;

            const totalChanges = (p.adds ?? 0) + (p.dels ?? 0);
            const AUTO_EXPAND_MAX = 50;

            // Auto-expand when complete and small enough
            if (
                (p.complete === true) &&
                totalChanges > 0 &&
                totalChanges <= AUTO_EXPAND_MAX
            ) {
                p.isExpanded = true;

                if (highlighter) {
                    const lang = getMdLanguageTag(p.filename);
                    const notLoaded = !highlighter.getLoadedLanguages().includes(lang);

                    const hast = highlighter.codeToHast(text, {
                        lang: notLoaded ? 'txt' : lang,
                        theme: 'css-variables',
                        transformers: [transformerDiffLines(added, removed)],
                    });

                    // Strip context lines (non-diff) and inter-line whitespace text
                    // nodes from the HAST so the browser never renders them.
                    stripContextLines(hast);

                    node.children = [hast];
                }
            }
        });
    };
}
