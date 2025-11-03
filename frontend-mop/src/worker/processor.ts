import type {Root as HastRoot} from 'hast';
import type {Parent, Root, RootContent} from 'mdast';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import { rehypeEditDiff } from './rehype/rehype-edit-diff';
import { rehypeToolCalls } from './rehype/rehype-tool-calls';
import type {HighlighterCore} from 'shiki/core';
import {type Processor, unified} from 'unified';
import {visit} from 'unist-util-visit';
import type {Test} from 'unist-util-visit';
import {editBlockFromMarkdown, gfmEditBlock} from '../lib/micromark-edit-block';
import type {OutboundFromWorker, ShikiLangsReadyMsg} from './shared';
import {ensureLang} from './shiki/ensure-langs';
import {shikiPluginPromise} from './shiki/shiki-plugin';
import { resetForBubble } from '../lib/edit-block/id-generator';
import { createWorkerLogger } from '../lib/logging';

function post(msg: OutboundFromWorker) {
    self.postMessage(msg);
}

const workerLog = createWorkerLogger('md-processor');

export function createBaseProcessor(): Processor {
    // Core processor without Shiki; consumers add rehype plugins on top.
    // When Shiki is enabled, rehypeToolCalls must run AFTER Shiki because Shiki replaces <pre> nodes;
    // tool-call annotations must be applied to the final <pre> that will be rendered.
    return unified()
        .use(remarkParse)
        .data('micromarkExtensions', [gfmEditBlock()])
        .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
        .use(remarkGfm)
        .use(remarkBreaks)
        .use(remarkRehype, {allowDangerousHtml: true}) as any;
}
 // processors
// Fast/base pipeline (no Shiki): add rehypeToolCalls directly.
let baseProcessor: Processor = (createBaseProcessor().use(rehypeToolCalls) as any);
let shikiProcessor: Processor = null;
let currentProcessor: Processor = baseProcessor;

// shiki-highlighter
export let highlighter: HighlighterCore | null = null;

export function initProcessor() {
    // Asynchronously initialize Shiki and create a new processor with it.
    workerLog.debug('[shiki] loading lib...');
    shikiPluginPromise
        .then(({rehypePlugin}) => {
            const [pluginFn, shikiHighlighter, opts] = rehypePlugin as any;
            highlighter = shikiHighlighter;
            shikiProcessor = createBaseProcessor()
                .use(pluginFn, shikiHighlighter, opts)
                // Important: run rehypeToolCalls AFTER Shiki since Shiki replaces <pre> nodes;
                // annotations must be applied to the final <pre> that will be rendered (avoids losing attributes).
                .use(rehypeToolCalls)
                .use(rehypeEditDiff, shikiHighlighter);
            currentProcessor = shikiProcessor;
            workerLog.debug('[shiki] loaded!');
            post(<ShikiLangsReadyMsg>{type: 'shiki-langs-ready'});
        })
        .catch(e => {
            workerLog.error('Shiki init failed', e);
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

    const diffLangs = (tree as any).data?.detectedDiffLangs as Set<string> | undefined;
    const detectedArr = Array.from(detectedLangs);
    const diffArr = diffLangs ? Array.from(diffLangs) : undefined;
    workerLog.debug('detected langs', detectedArr, diffArr);
    diffLangs?.forEach(l => detectedLangs.add(l));
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
        workerLog.error('parse failed', e);
        throw e;
    }
    if (!fast && highlighter) {
        // detect langs in the shiki highlighting pass to load lang lazy
        const detectedLangs = detectCodeFenceLangs(tree as any);
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
