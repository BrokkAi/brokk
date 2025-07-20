import type {Root as HastRoot} from 'hast';
import type {Parent, Root, RootContent} from 'mdast';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import type {HighlighterCore} from 'shiki/core';
import {type Processor, unified} from 'unified';
import {visit} from 'unist-util-visit';
import type {Test} from 'unist-util-visit';
import {editBlockFromMarkdown, gfmEditBlock} from '../lib/micromark-edit-block';
import type {OutboundFromWorker, ShikiLangsReadyMsg} from './shared';
import {ensureLang} from './shiki/ensure-langs';
import {shikiPluginPromise} from './shiki/shiki-plugin';
import { resetForBubble } from '../lib/edit-block/id-generator';


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
        .use(remarkRehype, {allowDangerousHtml: true}) as any;
}
// processors
let baseProcessor: Processor = createBaseProcessor();
let shikiProcessor: Processor = null;
let currentProcessor: Processor = baseProcessor;

// shiki-highlighter
export let highlighter: HighlighterCore | null = null;

export function initProcessor() {
    // Asynchronously initialize Shiki and create a new processor with it.
    console.log('[shiki] loading lib...');
    shikiPluginPromise
        .then(({rehypePlugin}) => {
            const [pluginFn, shikiHighlighter, opts] = rehypePlugin as any;
            highlighter = shikiHighlighter;
            shikiProcessor = createBaseProcessor().use(pluginFn, shikiHighlighter, opts);
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
    visit<Root, Test>(tree, (n): n is RootContent => n.type !== 'inlineCode', (node: any, index: number | undefined, parent: Parent | undefined) => {
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
export function parseMarkdown(seq: number, src: string, fast = false): HastRoot {
    const timeLabel = fast ? 'parse (fast)' : 'parse';
    console.time(timeLabel);
    const proc = fast ? baseProcessor : currentProcessor;
    let tree: HastRoot = null;
    try {
        // Reset the edit block ID counter before parsing
        resetForBubble(seq);
        tree = proc.runSync(proc.parse(src)) as HastRoot;
    } catch (e) {
        console.error('[md-worker] parse failed', e);
        throw e;
    }
    if (!fast && highlighter) {
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
    const pendingPromises = [...detectedLangs].map(ensureLang);

    if (pendingPromises.length > 0) {
        Promise.all(pendingPromises).then(results => {
            if (results.some(Boolean)) {
                post(<ShikiLangsReadyMsg>{type: 'shiki-langs-ready'});
            }
        });
    }
}
