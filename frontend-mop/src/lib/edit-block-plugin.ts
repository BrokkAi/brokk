import type {Code, Content, Node, Parent, Root, RootContent, Text} from 'mdast';
import {toString} from 'mdast-util-to-string';
import {SKIP, visit, type VisitorResult} from 'unist-util-visit';
import type {VFile} from 'vfile';

// Augment mdast to recognize custom data properties
declare module 'mdast' {
    interface Data {
        'edit-block'?: {
            hName: 'edit-block';
            hProperties: EditBlockProps;
        };
    }
}

interface EditBlockProps {
    id: string;
    filename: string;
    search: string;
    replace: string;
    adds: string;
    dels: string;
    changed: string;
    status: string;
}

interface ParsedResult {
    filename: string;
    search: string;
    replace: string;
    adds: number;
    dels: number;
    changed: number;
}

const HEAD = /^ {0,3}<{5,9}\s+SEARCH(?:\s+(.*))?\s*$/m;
const DIVIDER = /^ {0,3}={5,9}/;
const UPDATED = /^ {0,3}>{5,9}\s+REPLACE/;
const FENCE = /^ {0,3}```(?:\s*(\S[^`\s]*))?\s*$/;

function looksLikePath(s: string): boolean {
    return s.includes('.') || s.includes('/');
}

function stripFilename(line: string): string | null {
    let s = line.trim();
    if (s === '...' || s.startsWith('```')) {
        return null;
    }
    s = s.replace(/:$/, '').replace(/^#/, '').trim();
    s = s.replace(/^`+|`+$/, '');
    s = s.replace(/^\*+|\*+$/, '');
    return s || null;
}

function stripQuotedWrapping(block: string, fname: string): string {
    if (block.length === 0) {
        return block;
    }
    const lines = block.split('\n');
    if (fname && lines.length > 0) {
        const fn = fname.split('/').pop() || fname;
        if (lines[0].trim().endsWith(fn)) {
            lines.shift();
        }
    }
    if (lines.length >= 2 && lines[0].startsWith('```') && lines[lines.length - 1].startsWith('```')) {
        lines.shift();
        lines.pop();
    }
    let result = lines.join('\n').trimEnd();
    if (result && !result.endsWith('\n')) {
        result += '\n';
    }
    return result;
}

function scanLines(lines: string[], initialFilename = ''): ParsedResult | null {
    enum Phase { FILENAME, SEARCH, REPLACE }

    let phase = Phase.FILENAME;
    let filename = initialFilename;
    let searchBuf: string[] = [];
    let replaceBuf: string[] = [];
    let sawHead = false;

    for (const line of lines) {
        switch (phase) {
            case Phase.FILENAME: {
                const m = line.match(HEAD);
                if (m) {
                    sawHead = true;
                    if (m[1]) filename = m[1].trim();
                    phase = Phase.SEARCH;
                    continue;
                }
                if (!sawHead) {
                    const stripped = stripFilename(line);
                    if (stripped && looksLikePath(stripped)) {
                        filename = stripped;
                        continue;
                    }
                }
                break;
            }
            case Phase.SEARCH: {
                if (DIVIDER.test(line)) {
                    phase = Phase.REPLACE;
                    continue;
                }
                searchBuf.push(line);
                continue;
            }
            case Phase.REPLACE: {
                if (UPDATED.test(line)) {
                    // Complete block found, we can return the full result immediately.
                    const search = stripQuotedWrapping(searchBuf.join('\n'), filename);
                    const replace = stripQuotedWrapping(replaceBuf.join('\n'), filename);
                    const lineCount = (s: string) => {
                        const t = s.trim();
                        return t ? t.split('\n').length : 0;
                    };
                    const dels = lineCount(search);
                    const adds = lineCount(replace);
                    const changed = Math.min(adds, dels);
                    return {
                        filename: filename || '?',
                        search,
                        replace,
                        adds,
                        dels,
                        changed
                    };
                }
                replaceBuf.push(line);
                continue;
            }
        }
    }

    // If we saw a HEAD but not a TAIL, this is an incomplete (streaming) block.
    // Return a partial result.
    if (sawHead) {
        const search = stripQuotedWrapping(searchBuf.join('\n'), filename);
        const replace = stripQuotedWrapping(replaceBuf.join('\n'), filename);
        const lineCount = (s: string) => {
            const t = s.trim();
            return t ? t.split('\n').length : 0;
        };
        const dels = lineCount(search);
        const adds = lineCount(replace);
        const changed = Math.min(adds, dels);
        return {
            filename: filename || '?',
            search,
            replace,
            adds,
            dels,
            changed
        };
    }

    return null;
}

function fenceLooksLikeEditBlock(lines: string[]): boolean {
    const maxLook = 25;
    for (let i = 0; i < Math.min(lines.length, maxLook); i++) {
        if (HEAD.test(lines[i])) return true;
        if (FENCE.test(lines[i])) return false; // any subsequent fence (opening or closing) means not an edit block
    }
    return false;
}

function findFileNameNearby(allLines: string[], startIndex: number): string | null {
    const maxLookBack = 3;
    const start = Math.max(0, startIndex - maxLookBack);
    for (let i = startIndex - 1; i >= start; i--) {
        const line = allLines[i];
        if (!line.startsWith('```')) {
            const possible = stripFilename(line);
            if (possible && looksLikePath(possible)) {
                return possible;
            }
        }
    }
    return null;
}

function rawOf(node: Node & {
    position?: { start: { offset: number }; end: { offset: number } }
}, file: VFile): string {
    if (node.position && file.value) {
        return file.value.slice(
            node.position.start.offset,
            node.position.end.offset
        );
    }
    return toString(node);
}

function parseEditBlock(lines: string[], lang: string | null, parentLineIndex: number, allLines: string[]): ParsedResult | null {
    let filename = lang && looksLikePath(lang) ? lang : '';
    const result = scanLines(lines, filename);
    if (result && !filename && result.filename === '?') {
        if (parentLineIndex > 0) {
            const nearby = findFileNameNearby(allLines, parentLineIndex);
            if (nearby) result.filename = nearby;
        }
    }
    return result;
}

function handleFencedBlock(node: Code, index: number, parent: Parent, tree: Root, file: VFile, id: number): VisitorResult {
    const raw = toString(node);
    if (raw.includes('<<<<<<< SEARCH')) {
        const lines = raw.split('\n');
        if (fenceLooksLikeEditBlock(lines)) {
            const lang = node.lang; // sometimes lang == filename
            const allLines = file.value ? file.value.toString().split('\n') : [];
            const parsed = parseEditBlock(lines, lang, node.position?.start.line || 0, allLines);
            if (parsed) {
                const {filename, search, replace, adds, dels, changed} = parsed;
                const replacement: Text & { data: { hName: 'edit-block'; hProperties: EditBlockProps } } = {
                    type: 'text',
                    value: '',
                    data: {
                        hName: 'edit-block',
                        hProperties: {
                            id: String(id),
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
                parent.children.splice(index, 1, replacement);
                return [SKIP, index + 1];
            }
        }
    }
    return;
}

function handleUnfencedBlock(node: Content, index: number, parent: Parent, tree: Root, file: VFile, id: number): VisitorResult {
    let raw = rawOf(node, file);
    if (!raw.includes('<<<<<<< SEARCH')) return;

    // Accumulate siblings until closing marker
    let end = index;
    while (
        end < parent.children.length &&
        !raw.includes('>>>>>>> REPLACE')
        ) {
        end += 1;
        if (end >= parent.children.length) break;
        raw += '\n' + rawOf(parent.children[end], file);
    }

    // Parse
    const lines = raw.split('\n');
    const allLines = file.value ? file.value.toString().split('\n') : [];
    const parsed = parseEditBlock(
        lines,
        null,
        node.position?.start.line || 0,
        allLines
    );
    if (!parsed) return;

    const {filename, search, replace, adds, dels, changed} = parsed;

    const replacement: Text & { data: { hName: 'edit-block'; hProperties: EditBlockProps } } = {
        type: 'text',
        value: '',
        data: {
            hName: 'edit-block',
            hProperties: {
                id: String(id),
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

    // Replace the slice [index … end]
    parent.children.splice(index, end - index + 1, replacement);
    return [SKIP, index + 1]; // skip over the newly inserted node
}

export function remarkEditBlock(): (tree: Root, file: VFile) => void {
    let id = 0;
    // return (tree: Root, file: VFile) => {
    //     visit<Root, Test>(tree, (n): n is RootContent => n.type !== 'inlineCode', (node: RootContent, index: number | undefined, parent: Parent | undefined) => {
    //         if (index === undefined || parent === undefined) return;
    //         if (node.type === 'code') {
    //             return handleFencedBlock(node as Code, index, parent, tree, file, ++id);
    //         } else {
    //             return handleUnfencedBlock(node, index, parent, tree, file, ++id);
    //         }
    //     });
    // };

    return (tree: Root, file: VFile) => {
        visit<Root, Test>(tree, (n): n is RootContent => n.type !== 'inlineCode', (node: RootContent, index: number | undefined, parent: Parent | undefined) => {
            if (node.type === 'editBlock') {
                const data = node.data;
                node.data = {
                    hName: 'edit-block',
                    hProperties: {
                        id: String(id++),
                        filename: data.filename,
                        search: data.search,
                        replace: data.replace,
                    }
                }
                console.log('node', node);
            }

            return;
        });
    };
}

