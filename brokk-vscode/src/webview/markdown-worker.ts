/**
 * Web Worker that runs the full markdown pipeline (with Shiki syntax highlighting)
 * in a separate thread. The main thread sends markdown text, receives HTML back.
 *
 * Key architectural choices:
 * - Static imports from shiki/core, shiki/engine/javascript, shiki/langs/*.mjs
 *   (avoids dynamic import issues that plague shiki/bundle/web with esbuild IIFE)
 * - ESM format build (preserves module semantics)
 * - @shikijs/rehype/core for code block highlighting (takes pre-initialized highlighter)
 */

import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import remarkRehype from 'remark-rehype';
import rehypeStringify from 'rehype-stringify';
import rehypeShikiFromHighlighter from '@shikijs/rehype/core';
import {createCssVariablesTheme, createHighlighterCore} from 'shiki/core';
import {createJavaScriptRegexEngine} from 'shiki/engine/javascript';

// Static language grammar imports (same as MOP — avoids dynamic loading)
import js from 'shiki/langs/javascript.mjs';
import ts from 'shiki/langs/typescript.mjs';
import python from 'shiki/langs/python.mjs';
import java from 'shiki/langs/java.mjs';
import bash from 'shiki/langs/bash.mjs';
import json from 'shiki/langs/json.mjs';
import yaml from 'shiki/langs/yaml.mjs';
import markdown from 'shiki/langs/markdown.mjs';

// Our custom plugins
import {gfmEditBlock} from '../markdown/edit-block/extension';
import {editBlockFromMarkdown} from '../markdown/edit-block/from-markdown';
import {rehypeToolCalls} from '../markdown/rehype-tool-calls';
import {rehypeEditDiffSimple} from '../markdown/rehype-edit-diff-simple';
import {rehypeVscodeRender} from '../markdown/rehype-vscode-render';
import {resetForBubble} from '../markdown/edit-block/id-generator';

// CSS variables theme — generates style="color: var(--shiki-token-...)" instead of literal hex
const cssVarsTheme = createCssVariablesTheme({
    name: 'css-variables',
    variablePrefix: '--shiki-',
    variableDefaults: {},
    fontStyle: true,
});

let processor: ReturnType<typeof unified> | null = null;

async function init() {
    try {
        const hl = await createHighlighterCore({
            themes: [cssVarsTheme],
            langs: [js, ts, python, java, bash, json, yaml, markdown],
            engine: createJavaScriptRegexEngine(),
        });

        // Pipeline ordering matches MOP:
        // 1. rehypeShiki — highlights all <pre><code> blocks
        // 2. rehypeToolCalls — annotates tool-call YAML blocks (must run AFTER Shiki
        //    because Shiki replaces <pre> nodes)
        // 3. rehypeEditDiffSimple — computes diffs for edit blocks, uses Shiki for highlighting
        // 4. rehypeVscodeRender — transforms edit-block/tool-call nodes to <details> HTML
        // 5. rehypeStringify — serializes HAST to HTML string
        processor = unified()
            .use(remarkParse)
            .data('micromarkExtensions', [gfmEditBlock()])
            .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
            .use(remarkGfm)
            .use(remarkBreaks)
            .use(remarkRehype, {allowDangerousHtml: true})
            .use(rehypeShikiFromHighlighter, hl as any, {
                theme: 'css-variables',
            })
            .use(rehypeToolCalls)
            .use(() => rehypeEditDiffSimple(hl as any))
            .use(rehypeVscodeRender)
            .use(rehypeStringify);

        self.postMessage({type: 'ready'});
    } catch (err) {
        console.error('[Brokk Worker] Shiki init failed:', err);
        self.postMessage({
            type: 'error',
            message: err instanceof Error ? err.message : String(err),
        });
    }
}

self.onmessage = (ev: MessageEvent) => {
    try {
        const msg = ev.data;
        if (msg.type === 'ping') {
            self.postMessage({type: 'pong'});
            return;
        }
        if (msg.type === 'render') {
            if (!processor) {
                self.postMessage({type: 'result', seq: msg.seq, html: null, error: 'Worker not ready'});
                return;
            }
            resetForBubble(msg.seq);
            const result = processor.processSync(msg.text);
            const html = String(result);
            self.postMessage({type: 'result', seq: msg.seq, html});
        }
    } catch (err) {
        const error = err instanceof Error ? err.message + '\n' + err.stack : String(err);
        try {
            self.postMessage({type: 'result', seq: ev.data?.seq ?? -1, html: null, error});
        } catch (_) {
            // Last resort
        }
    }
};

// Start async initialization
init();
