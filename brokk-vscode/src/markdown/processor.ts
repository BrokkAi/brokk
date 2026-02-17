/**
 * Main-thread markdown processor — fast pipeline only (no Shiki).
 * Full syntax highlighting runs in a Web Worker (markdown-worker.ts).
 */
import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import remarkRehype from 'remark-rehype';
import rehypeStringify from 'rehype-stringify';
import {gfmEditBlock} from './edit-block/extension';
import {editBlockFromMarkdown} from './edit-block/from-markdown';
import {rehypeToolCalls} from './rehype-tool-calls';
import {rehypeEditDiffSimple} from './rehype-edit-diff-simple';
import {rehypeVscodeRender} from './rehype-vscode-render';
import {resetForBubble} from './edit-block/id-generator';

const fastProcessor = unified()
    .use(remarkParse)
    .data('micromarkExtensions', [gfmEditBlock()])
    .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
    .use(remarkGfm)
    .use(remarkBreaks)
    .use(remarkRehype, {allowDangerousHtml: true})
    .use(rehypeToolCalls)
    .use(() => rehypeEditDiffSimple(null))
    .use(rehypeVscodeRender)
    .use(rehypeStringify);

let bubbleCounter = 0;

/**
 * Fast render (no Shiki) — used during streaming on the main thread.
 * Full renders with syntax highlighting are handled by the Web Worker.
 */
export function renderMarkdownFast(text: string): string {
    try {
        resetForBubble(bubbleCounter++);
        const result = fastProcessor.processSync(text);
        return String(result);
    } catch (err) {
        console.error('[Brokk] Markdown render error:', err);
        return escapeHtml(text);
    }
}

function escapeHtml(str: string): string {
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
