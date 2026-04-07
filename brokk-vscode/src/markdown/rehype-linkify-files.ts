import {visit} from 'unist-util-visit';
import type {Root, Element, Text} from 'hast';
import {looksLikeFilePath, parseFilePathWithLines} from './filePathDetection';

/**
 * Regex to find file-path-like tokens in text.
 * Matches sequences that contain a dot-extension and optional :line or :line-line suffix,
 * bounded by whitespace, backticks, quotes, or start/end of string.
 */
const FILE_PATH_RE = /(?:^|(?<=[\s`"'(]))([a-zA-Z0-9_./\\-]+\.[a-zA-Z0-9]+(?::\d+(?:-\d+)?)?)(?=$|[\s`"'),;:\].])/g;

/**
 * Rehype plugin that detects file paths in text nodes and wraps them
 * in <a data-file="..."> elements so they become clickable in the webview.
 *
 * Skips text inside <code>, <pre>, <a>, and edit-block elements.
 */
export function rehypeLinkifyFiles() {
    return (tree: Root) => {
        visit(tree, 'text', (node: Text, index: number | undefined, parent: any) => {
            if (!parent || index == null) return;

            // Skip text inside elements where we shouldn't linkify
            const tag = parent.tagName;
            if (tag === 'code' || tag === 'pre' || tag === 'a' ||
                tag === 'edit-block' || tag === 'summary') return;

            const text = node.value;
            const matches: Array<{start: number; end: number; path: string; original: string}> = [];

            let match;
            FILE_PATH_RE.lastIndex = 0;
            while ((match = FILE_PATH_RE.exec(text)) !== null) {
                const candidate = match[1];
                if (looksLikeFilePath(candidate)) {
                    const parsed = parseFilePathWithLines(candidate);
                    matches.push({
                        start: match.index,
                        end: match.index + candidate.length,
                        path: parsed.path,
                        original: candidate
                    });
                }
            }

            if (matches.length === 0) return;

            // Build replacement children: text nodes interspersed with <a> elements
            const children: Array<Text | Element> = [];
            let lastEnd = 0;

            for (const m of matches) {
                if (m.start > lastEnd) {
                    children.push({type: 'text', value: text.slice(lastEnd, m.start)});
                }
                children.push({
                    type: 'element',
                    tagName: 'a',
                    properties: {
                        'data-file': m.path,
                        href: '#',
                        className: ['file-link'],
                        title: m.path
                    },
                    children: [{type: 'text', value: m.original}]
                });
                lastEnd = m.end;
            }

            if (lastEnd < text.length) {
                children.push({type: 'text', value: text.slice(lastEnd)});
            }

            // Replace the text node with the new children
            parent.children.splice(index, 1, ...children);

            // Return the index to skip past our newly inserted nodes
            return index + children.length;
        });
    };
}
