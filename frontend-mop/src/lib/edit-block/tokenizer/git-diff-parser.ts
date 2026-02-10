import type { Code, State, Tokenizer } from 'micromark-util-types';
import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import { makeSafeFx } from '../util';

/**
 * Parser for git diff format: [--- file, +++ file, @@ changes @@, content...]
 * Extracts filename and content, then reconstructs before/after content for diff rendering
 */
export interface GitDiffParseResult {
    filename: string;
    beforeContent: string;
    afterContent: string;
}

/**
 * Tokenizer for git diff format inside fenced code blocks
 */
export const tokenizeGitDiff: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('gitDiff', effects, ctx, ok, nok);

    return start;

    function start(code: Code): State {
        if (code !== codes.leftSquareBracket) { // [
            return fx.nok(code);
        }

        fx.enter('gitDiffContent');
        return collectContent(code);
    }

    function collectContent(code: Code): State {
        if (code === codes.rightSquareBracket) { // ]
            fx.exit('gitDiffContent');
            return fx.ok(code);
        }

        if (code === codes.eof) {
            fx.exit('gitDiffContent');
            return fx.ok(code);
        }

        // Wrap content in data tokens like body tokenizer does
        fx.enter('data');
        fx.consume(code);
        fx.exit('data');
        return collectContent;
    }
};

/**
 * Parse git diff content in the format: [--- file, +++ file, @@ changes @@, content...]
 */
export function parseGitDiffContent(content: string): GitDiffParseResult {

    // Remove the opening [ and closing ] if present, also remove trailing ```
    let trimmed = content.replace(/^\[/, '').replace(/\]```\s*$/, '').replace(/\]$/, '');

    // Split by comma and trim each part
    const parts = trimmed.split(',').map(p => p.trim());

    if (parts.length < 3) {
        throw new Error('Invalid git diff format: not enough parts');
    }

    // Extract filename from --- or +++ parts, prefer +++ (new file)
    let filename = '?';
    for (const part of parts) {
        if (part.startsWith('+++ ')) {
            const filePath = part.substring(4).trim();
            if (filePath && filePath !== '/dev/null') {
                // Extract just the filename from the path
                const pathParts = filePath.split('/');
                filename = pathParts[pathParts.length - 1];
                break;
            }
        }
    }
    // If no +++ found, try --- (old file)
    if (filename === '?') {
        for (const part of parts) {
            if (part.startsWith('--- ')) {
                const filePath = part.substring(4).trim();
                if (filePath && filePath !== '/dev/null') {
                    // Extract just the filename from the path
                    const pathParts = filePath.split('/');
                    filename = pathParts[pathParts.length - 1];
                    break;
                }
            }
        }
    }

    // Find the @@ part and skip metadata
    // Look for parts that start with @@ and reconstruct the full @@ section
    let contentStartIndex = -1;
    for (let i = 0; i < parts.length; i++) {
        if (parts[i].startsWith('@@')) {
            // Find the end of the @@ section
            let endIndex = i;
            while (endIndex < parts.length && !parts[endIndex].endsWith('@@')) {
                endIndex++;
            }
            if (endIndex < parts.length && parts[endIndex].endsWith('@@')) {
                contentStartIndex = endIndex + 1;
                break;
            }
        }
    }

    if (contentStartIndex === -1) {
        throw new Error('Invalid git diff format: no @@ section found');
    }

    // Process content parts
    const contentParts = parts.slice(contentStartIndex);
    const beforeLines: string[] = [];
    const afterLines: string[] = [];

    for (const part of contentParts) {
        if (!part) continue; // Skip empty parts

        if (part.startsWith('- ')) {
            // Removed line (only in before)
            const line = part.substring(2);
            beforeLines.push(line);
        } else if (part.startsWith('+ ')) {
            // Added line (only in after)
            const line = part.substring(2);
            afterLines.push(line);
        } else if (part.startsWith('-')) {
            // Removed line without space
            const line = part.substring(1);
            beforeLines.push(line);
        } else if (part.startsWith('+')) {
            // Added line without space
            const line = part.substring(1);
            afterLines.push(line);
        } else {
            // Unchanged line (in both)
            beforeLines.push(part);
            afterLines.push(part);
        }
    }

    const result = {
        filename,
        beforeContent: beforeLines.join('\n'),
        afterContent: afterLines.join('\n')
    };

    return result;
}