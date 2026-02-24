/**
 * Markdown processor with highlight.js syntax highlighting.
 * Used everywhere — streaming and finalization — no worker needed.
 */
import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import remarkRehype from 'remark-rehype';
import rehypeHighlight from 'rehype-highlight';
import rehypeStringify from 'rehype-stringify';
import {gfmEditBlock} from './edit-block/extension';
import {editBlockFromMarkdown} from './edit-block/from-markdown';
import {rehypeToolCalls} from './rehype-tool-calls';
import {rehypeEditDiffSimple} from './rehype-edit-diff-simple';
import {rehypeVscodeRender} from './rehype-vscode-render';
import {resetForBubble} from './edit-block/id-generator';

const processor = unified()
    .use(remarkParse)
    .data('micromarkExtensions', [gfmEditBlock()])
    .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
    .use(remarkGfm)
    .use(remarkBreaks)
    .use(remarkRehype, {allowDangerousHtml: true})
    .use(rehypeHighlight, {detect: true, ignoreMissing: true})
    .use(rehypeToolCalls)
    .use(rehypeEditDiffSimple)
    .use(rehypeVscodeRender)
    .use(rehypeStringify);

let bubbleCounter = 0;

/**
 * Render markdown to HTML with syntax highlighting.
 * Used during streaming and finalization.
 */
export function renderMarkdownFast(text: string): string {
    if (!text) return '';
    try {
        resetForBubble(bubbleCounter++);
        const result = processor.processSync(text);
        return String(result);
    } catch (err) {
        console.error('[Brokk] Markdown render error:', err);
        return escapeHtml(text);
    }
}

function escapeHtml(str: string): string {
    return String(str ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
