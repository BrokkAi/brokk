import {editBlockFromMarkdown, gfmEditBlock} from '../lib/micromark-edit-block';
import {remarkEditBlock} from '../lib/edit-block-plugin';
import type {Root as HastRoot} from 'hast';
import type {Parent, Root, RootContent} from 'mdast';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import type {HighlighterCore} from 'shiki/core';
import {type Processor, unified} from 'unified';
import {visit} from 'unist-util-visit';
import type {Test} from 'unist-util-visit/lib';
import type {OutboundFromWorker, ShikiLangsReadyMsg} from './shared';
import {ensureLang, shikiPluginPromise} from './shiki/shiki-plugin';

function post(msg: OutboundFromWorker) {
    self.postMessage(msg);
}

export function createBaseProcessor(): Processor {
    return unified()
        .use(remarkParse)
        .data('micromarkExtensions', [gfmEditBlock()])
        .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
        .use(remarkGfm)
        .use(remarkBreaks)
        .use(remarkEditBlock)
        .use(remarkRehype, {allowDangerousHtml: true});
}

let baseProcessor: Processor = createBaseProcessor();
let currentProcessor: Processor = baseProcessor;
let highlighter: HighlighterCore | null = null;

export function initProcessor() {
    // Asynchronously initialize Shiki and create a new processor with it.
    console.log('[shiki] loading lib...');
    shikiPluginPromise
        .then(({rehypePlugin}) => {
            const [pluginFn, shikiHighlighter, opts] = rehypePlugin as any;
            highlighter = shikiHighlighter;
            const shikiProcessor = createBaseProcessor().use(pluginFn, shikiHighlighter, opts);
            currentProcessor = shikiProcessor;
            console.log('[shiki] loaded!');
            post(<ShikiLangsReadyMsg>{type: 'shiki-langs-ready'});
        })
        .catch(e => {
            console.error('[md-worker] Shiki init failed', e);
        });
}

function detectCodeFenceLangs(tree: Root): Set<string> {
    const detectedLangs = new Set<string>();
    visit<Root, Test>(tree, (n): n is RootContent => n.type !== 'inlineCode', (node: RootContent, index: number | undefined, parent: Parent | undefined) => {
        if (index === undefined || parent === undefined) return;
        if (node.tagName === 'code') {
            let lang = node.properties?.className?.[0];
            if (lang) {
                lang = lang.replace('language-', '');
                detectedLangs.add(lang);
            }
        }
    });
    return detectedLangs;
}

export function parseMarkdown(src: string, fast = false): HastRoot {
    fast = true;
    const timeLabel = fast ? 'parse (fast)' : 'parse';
    console.time(timeLabel);
    const proc = fast ? baseProcessor : currentProcessor;
    const tree = proc.runSync(proc.parse(src)) as HastRoot;
    if (!fast) {
        // detect langs in the shiki highlighting pass to load lang lazy
        const detectedLangs = detectCodeFenceLangs(tree);
        if (detectedLangs.size > 0) {
            handlePendingLanguages(detectedLangs);
        }
    }
    console.timeEnd(timeLabel);
    return tree;
}

function handlePendingLanguages(detectedLangs: Set<string>): void {
    const pendingPromises = [...detectedLangs]
        .filter(lang => lang && !highlighter!.getLoadedLanguages().includes(lang))
        .map(ensureLang);

    if (pendingPromises.length > 0) {
        Promise.all(pendingPromises).then(() => {
            post(<ShikiLangsReadyMsg>{type: 'shiki-langs-ready'});
        });
    }
}
