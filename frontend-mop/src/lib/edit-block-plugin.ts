import type { Plugin } from 'svelte-exmarkdown';
import type { Root, Node } from 'mdast';
import { visit } from 'unist-util-visit';
import { toString } from 'mdast-util-to-string';
import EditBlock from '../components/EditBlock.svelte';

const HEAD = /^ {0,3}<{5,9}\s+SEARCH(?:\s+(.*))?\s*$/;
const DIVIDER = /^ {0,3}={5,9}/;
const UPDATED = /^ {0,3}>{5,9}\s+REPLACE/;

function looksLikePath(s: string): boolean {
    return s.includes('.') || s.includes('/');
}

function parseEditBlock(lines: string[], lang: string | null): {
    filename: string;
    search: string;
    replace: string;
    adds: number;
    dels: number;
    changed: number;
} | null {
    let filename = '';
    if (lang && looksLikePath(lang)) {
        filename = lang;
    }

    const headIndex = lines.findIndex(line => HEAD.test(line));
    if (headIndex === -1) return null;

    const dividerIndex = lines.findIndex((line, i) => i > headIndex && DIVIDER.test(line));
    if (dividerIndex === -1) return null;

    const updatedIndex = lines.findIndex((line, i) => i > dividerIndex && UPDATED.test(line));
    if (updatedIndex === -1) return null;

    const headMatch = lines[headIndex].match(HEAD);
    if (headMatch && headMatch[1]) {
        filename = headMatch[1].trim();
    }

    const searchLines = lines.slice(headIndex + 1, dividerIndex);
    const replaceLines = lines.slice(dividerIndex + 1, updatedIndex);

    const search = searchLines.join('\n');
    const replace = replaceLines.join('\n');

    const getLineCount = (s: string) => {
        const trimmed = s.trim();
        return trimmed ? trimmed.split('\n').length : 0;
    };

    const dels = getLineCount(search);
    const adds = getLineCount(replace);
    const changed = Math.min(adds, dels);

    return { filename: filename || '?', search, replace, adds, dels, changed };
}

function remarkEditBlock() {
    let id = 0;
    return (tree: Root) => {
        visit(tree, ['code', 'paragraph', 'html'], (node: Node, index?: number, parent?: Node) => {
            if (index === undefined || !parent || !('children' in parent)) return;

            const raw = toString(node);
            if (!raw.includes('<<<<<<< SEARCH')) return;

            const lang = node.type === 'code' && 'lang' in node ? (node.lang as string) : null;
            const lines = raw.split('\n');
            const parsed = parseEditBlock(lines, lang);
            if (!parsed) return;

            const { filename, search, replace, adds, dels, changed } = parsed;

            const replacement = {
                type: 'text',
                value: '',
                data: {
                    hName: 'edit-block',
                    hProperties: {
                        id: String(++id),
                        filename,
                        search,
                        replace,
                        adds: String(adds),
                        dels: String(dels),
                        changed: String(changed),
                        status: 'UNKNOWN'
                    }
                }
            };

            parent.children.splice(index, 1, replacement as any);
            return ['skip', index + 1];
        });
    };
}


export const editBlockPlugin = (): Plugin => ({
    remarkPlugin: [remarkEditBlock],
    renderer: {
        'edit-block': EditBlock
    }
});
