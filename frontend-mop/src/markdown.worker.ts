/// <reference lib="webworker" />

import { expose } from 'comlink';
import { unified } from 'unified';
import type { Root as HastRoot } from 'hast';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
//import { remarkEditBlock } from './lib/edit-block-plugin';
import remarkRehype from 'remark-rehype';
import rehypeShiki from '@shikijs/rehype/core';

import { createHighlighterCore, createCssVariablesTheme } from 'shiki/core';
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript';
import { ensureLang, setHighlighter } from './worker-lang-loader';

const cssVarsTheme = createCssVariablesTheme({
    name: 'css-vars',
    variablePrefix: '--shiki-',
    variableDefaults: {},
    fontStyle: true
});

const languageAttributeTransformer = {
    name: 'add-language-attributes',
    pre(node) {
        node.properties = node.properties || {};
        node.properties['data-language'] = this.options.lang;
        return node;
    }
};

const highlighterPromise = createHighlighterCore({
    themes: [cssVarsTheme],
    langs: [], // start empty -> lazy
    engine: createJavaScriptRegexEngine()
});

let processor: ReturnType<typeof unified> | null = null;

highlighterPromise.then(highlighter => {
    setHighlighter(highlighter);
    processor = unified()
        .use(remarkParse)
        .use(remarkGfm)
        .use(remarkBreaks)
        // .use(remarkEditBlock)
        .use(remarkRehype, { allowDangerousHtml: true })
        .use(rehypeShiki, {
            highlighter,
            theme: 'css-vars',
            transformers: [languageAttributeTransformer],
            async getLanguage(lang) {
                await ensureLang(lang);
                return lang;
            }
        });
});


async function render(md: string): Promise<HastRoot> {
    await highlighterPromise;
    if (!processor) {
        throw new Error('Processor not initialized');
    }
    const mdast = processor.parse(md);
    const hast = await processor.run(mdast);
    return hast as HastRoot;
}

const workerApi = {
    render
};

export type MarkdownWorker = typeof workerApi;

expose(workerApi);
