import {visit} from 'unist-util-visit';
import type {Root, Element, Text} from 'hast';
import type {EditBlockProperties} from './types';
import {buildUnifiedDiff} from './diff-utils';

/**
 * Rehype plugin that transforms custom HAST nodes into standard HTML elements
 * that rehype-stringify can serialize. Specifically:
 *
 * 1. `<edit-block>` → `<details class="edit-block">` with filename header,
 *    +/- stats, and diff body
 * 2. `<pre data-tool-headline="...">` → `<details class="tool-call">` with
 *    headline summary and YAML body
 * 3. Strips `<img>` tags to avoid CSP issues
 */
export function rehypeVscodeRender() {
    return (tree: Root) => {
        // Pass 1: Transform edit-block nodes
        visit(tree, (n: any) => n.tagName === 'edit-block', (node: any, index: number, parent: any) => {
            if (!parent || index == null) return;

            const p: EditBlockProperties = node.properties ?? {};
            const filename = p.filename || '?';
            const basename = filename.split(/[\\/]/).pop() || filename;

            // Build header children — filename is clickable if we have a real path
            const filenameNode = filename !== '?'
                ? {
                    type: 'element',
                    tagName: 'a',
                    properties: {
                        className: ['edit-block-filename'],
                        'data-file': filename,
                        href: '#',
                        title: filename
                    },
                    children: [{type: 'text', value: basename}]
                }
                : {
                    type: 'element',
                    tagName: 'span',
                    properties: {className: ['edit-block-filename']},
                    children: [{type: 'text', value: basename}]
                };
            const headerChildren: any[] = [filenameNode];

            // Add stats if available
            const statsChildren: any[] = [];
            if (p.adds != null && p.adds > 0) {
                statsChildren.push({
                    type: 'element',
                    tagName: 'span',
                    properties: {className: ['edit-block-adds']},
                    children: [{type: 'text', value: `+${p.adds}`}]
                });
            }
            if (p.dels != null && p.dels > 0) {
                statsChildren.push({
                    type: 'element',
                    tagName: 'span',
                    properties: {className: ['edit-block-dels']},
                    children: [{type: 'text', value: `-${p.dels}`}]
                });
            }
            if (statsChildren.length > 0) {
                headerChildren.push({
                    type: 'element',
                    tagName: 'span',
                    properties: {className: ['edit-block-stats']},
                    children: statsChildren
                });
            }

            // If not yet headerOk, show loading state
            if (!p.headerOk) {
                const div: Element = {
                    type: 'element',
                    tagName: 'div',
                    properties: {className: ['edit-block']},
                    children: [
                        {
                            type: 'element',
                            tagName: 'div',
                            properties: {className: ['edit-block-header']},
                            children: [
                                ...headerChildren,
                                {
                                    type: 'element',
                                    tagName: 'span',
                                    properties: {className: ['edit-block-loading']},
                                    children: [{type: 'text', value: '...'}]
                                }
                            ]
                        }
                    ]
                };
                parent.children[index] = div;
                return;
            }

            // Build body content
            let bodyChildren: any[];
            if (p.isExpanded && node.children && node.children.length > 0 &&
                !(node.children.length === 1 && node.children[0].type === 'text' && node.children[0].value === '')) {
                // Shiki-highlighted diff content from rehype-edit-diff-simple
                bodyChildren = [{
                    type: 'element',
                    tagName: 'div',
                    properties: {className: ['edit-block-body']},
                    children: node.children
                }];
            } else {
                // Plain text fallback diff — use buildUnifiedDiff to compute
                // actual added/removed lines instead of naively marking everything
                const diffLines: any[] = [];
                const search = (p.search ?? '').replace(/^\n+|\n+$/g, '');
                const replace = (p.replace ?? '').replace(/^\n+|\n+$/g, '');
                const {text, added, removed} = buildUnifiedDiff(search, replace);
                const lines = text.split('\n');
                const addedSet = new Set(added);
                const removedSet = new Set(removed);
                const hasDiff = added.length > 0 || removed.length > 0;
                for (let i = 0; i < lines.length; i++) {
                    const lineNum = i + 1; // 1-indexed
                    const cls = addedSet.has(lineNum) ? 'diff-add'
                              : removedSet.has(lineNum) ? 'diff-del'
                              : null;
                    // Only changed lines get 'diff-line' — context lines get no class
                    // so they're hidden by the font-size:0 trick on pre.has-diff
                    const classNames = cls ? ['diff-line', cls] : [];
                    diffLines.push({
                        type: 'element',
                        tagName: 'span',
                        properties: classNames.length > 0 ? {className: classNames} : {},
                        children: [{type: 'text', value: lines[i]}]
                    });
                    if (i < lines.length - 1) {
                        diffLines.push({type: 'text', value: '\n'});
                    }
                }
                const preClasses = ['edit-block-diff'];
                if (hasDiff) preClasses.push('has-diff');
                bodyChildren = diffLines.length > 0 ? [{
                    type: 'element',
                    tagName: 'div',
                    properties: {className: ['edit-block-body']},
                    children: [{
                        type: 'element',
                        tagName: 'pre',
                        properties: {className: preClasses},
                        children: diffLines
                    }]
                }] : [];
            }

            // Build the <details> element
            const details: Element = {
                type: 'element',
                tagName: 'details',
                properties: {
                    className: ['edit-block'],
                    open: p.isExpanded || false
                },
                children: [
                    {
                        type: 'element',
                        tagName: 'summary',
                        properties: {className: ['edit-block-header']},
                        children: headerChildren
                    },
                    ...bodyChildren
                ]
            };

            parent.children[index] = details;
        });

        // Pass 2: Transform tool-call annotated <pre> elements
        visit(tree, (n: any) => {
            if (n.type !== 'element') return false;
            // Check for Shiki fragments (root node wrapping a single <pre>)
            if (n.type === 'root' && Array.isArray(n.children) && n.children.length === 1) {
                const child = n.children[0];
                return child?.type === 'element' && child?.tagName === 'pre' && child?.properties?.['data-tool-headline'];
            }
            return n.tagName === 'pre' && n.properties?.['data-tool-headline'];
        }, (node: any, index: number, parent: any) => {
            if (!parent || index == null) return;

            // Get the actual <pre> element (might be inside a Shiki fragment root)
            let pre: Element;
            if (node.type === 'root' && Array.isArray(node.children)) {
                pre = node.children[0] as Element;
            } else {
                pre = node as Element;
            }

            const headline = String(pre.properties?.['data-tool-headline'] || '');
            if (!headline) return;

            // Remove the annotation properties
            delete (pre.properties as any)['data-tool-headline'];
            delete (pre.properties as any)['data-collapse-default'];

            const details: Element = {
                type: 'element',
                tagName: 'details',
                properties: {className: ['tool-call']},
                children: [
                    {
                        type: 'element',
                        tagName: 'summary',
                        properties: {className: ['tool-call-header']},
                        children: [{type: 'text', value: headline}]
                    },
                    {
                        type: 'element',
                        tagName: 'div',
                        properties: {className: ['tool-call-body']},
                        children: [pre]
                    }
                ]
            };

            parent.children[index] = details;
        });

        // Pass 3: Remove <img> elements (CSP safety)
        visit(tree, {type: 'element', tagName: 'img'} as any, (node: any, index: number, parent: any) => {
            if (parent && index != null) {
                parent.children.splice(index, 1);
                return index; // Re-visit this index since we removed an element
            }
        });
    };
}
