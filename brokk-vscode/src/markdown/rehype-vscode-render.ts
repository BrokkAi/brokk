import {visit} from 'unist-util-visit';
import type {Root, Element, ElementContent} from 'hast';
import type {EditBlockProperties} from './types';
import {getMdLanguageTag} from './diff-utils';
import {createLowlight} from 'lowlight';
import {common} from 'lowlight';

const lowlight = createLowlight(common);

/**
 * Split lowlight's flat HAST children on '\n' boundaries into per-line groups.
 * Each group is an array of HAST children representing one line.
 */
function splitHastByLine(children: ElementContent[]): ElementContent[][] {
    const lines: ElementContent[][] = [[]];
    for (const child of children) {
        if (child.type === 'text') {
            const parts = child.value.split('\n');
            for (let i = 0; i < parts.length; i++) {
                if (i > 0) lines.push([]);
                if (parts[i]) {
                    lines[lines.length - 1].push({type: 'text', value: parts[i]});
                }
            }
        } else if (child.type === 'element') {
            // Element may contain text with newlines — need to split recursively
            const textContent = getTextContent(child);
            if (textContent.includes('\n')) {
                // Split the element's text content across lines
                const subLines = splitHastByLine(child.children as ElementContent[]);
                for (let i = 0; i < subLines.length; i++) {
                    if (i > 0) lines.push([]);
                    if (subLines[i].length > 0) {
                        lines[lines.length - 1].push({
                            ...child,
                            children: subLines[i] as any
                        });
                    }
                }
            } else {
                lines[lines.length - 1].push(child);
            }
        } else {
            lines[lines.length - 1].push(child);
        }
    }
    return lines;
}

function getTextContent(node: any): string {
    if (node.type === 'text') return node.value;
    if (node.children) return node.children.map(getTextContent).join('');
    return '';
}

/**
 * Rehype plugin that transforms custom HAST nodes into standard HTML elements
 * that rehype-stringify can serialize. Specifically:
 *
 * 1. `<edit-block>` → `<details class="edit-block">` with filename header,
 *    +/- stats, and syntax-highlighted diff body
 * 2. `<pre data-tool-headline="...">` → `<details class="tool-call">` with
 *    headline summary and YAML body
 * 3. Strips `<img>` tags to avoid CSP issues
 */
export function rehypeVscodeRender() {
    return (tree: Root) => {
        // Pass 1: Transform edit-block nodes
        visit(tree, (n: any) => n.tagName === 'edit-block', (node: any, index: number | undefined, parent: any) => {
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

            // Build body content with syntax-highlighted diff
            // Use pre-computed diff from rehypeEditDiffSimple
            let bodyChildren: any[] = [];

            const text = p.diffText ?? '';
            const added = p.diffAdded ?? [];
            const removed = p.diffRemoved ?? [];

            if (added.length > 0 || removed.length > 0) {
                const addedSet = new Set(added);
                const removedSet = new Set(removed);

                // Highlight the full diff text
                const lang = getMdLanguageTag(p.filename || '');
                let highlighted;
                try {
                    highlighted = lang && lowlight.listLanguages().includes(lang)
                        ? lowlight.highlight(lang, text)
                        : lowlight.highlightAuto(text);
                } catch {
                    highlighted = lowlight.highlightAuto(text);
                }

                // Split highlighted HAST into per-line groups
                const hastLines = splitHastByLine(highlighted.children as ElementContent[]);

                // Only emit diff-add and diff-del lines (skip context)
                const diffLines: any[] = [];
                for (let i = 0; i < hastLines.length; i++) {
                    const lineNum = i + 1;
                    const isAdd = addedSet.has(lineNum);
                    const isDel = removedSet.has(lineNum);
                    if (!isAdd && !isDel) continue;

                    const cls = isAdd ? 'diff-add' : 'diff-del';
                    diffLines.push({
                        type: 'element',
                        tagName: 'span',
                        properties: {className: ['diff-line', cls]},
                        children: hastLines[i].length > 0 ? hastLines[i] : [{type: 'text', value: ''}]
                    });
                }

                if (diffLines.length > 0) {
                    bodyChildren = [{
                        type: 'element',
                        tagName: 'div',
                        properties: {className: ['edit-block-body']},
                        children: [{
                            type: 'element',
                            tagName: 'pre',
                            properties: {className: ['edit-block-diff']},
                            children: diffLines
                        }]
                    }];
                }
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
            return n.tagName === 'pre' && n.properties?.['data-tool-headline'];
        }, (node: any, index: number | undefined, parent: any) => {
            if (!parent || index == null) return;

            const pre = node as Element;
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
        visit(tree, {type: 'element', tagName: 'img'} as any, (node: any, index: number | undefined, parent: any) => {
            if (parent && index != null) {
                parent.children.splice(index, 1);
                return index; // Re-visit this index since we removed an element
            }
        });
    };
}
