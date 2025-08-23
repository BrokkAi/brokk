import type {Root as HastRoot} from 'hast';
import type {Parent, Root, RootContent} from 'mdast';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import { rehypeEditDiff } from './rehype/rehype-edit-diff';
import { rehypeSymbolLookup } from './rehype/rehype-symbol-lookup';
import {
    handleSymbolLookupWithContextRequest,
    handleSymbolLookupWithContextRequestStreaming,
    handleSymbolLookupResponse as handleSymbolLookupResponseInternal,
    clearContextCache,
    clearSymbolCache
} from './symbol-lookup';
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

const workerLog = createWorkerLogger('processor');
// Flag to disable symbol resolution for testing
const SKIP_SYMBOL_RESOLUTION = false;

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
        .use(remarkRehype, {allowDangerousHtml: true})// as any;
        .use(rehypeSymbolLookup) as any;
}

export function createFastProcessor(): Processor {
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
let fastBaseProcessor: Processor = createFastProcessor();
let shikiProcessor: Processor = null;
let currentProcessor: Processor = baseProcessor;
// shiki-highlighter
export let highlighter: HighlighterCore | null = null;

export function initProcessor() {
    console.log('[shiki] loading lib...');
    shikiPluginPromise
        .then(({rehypePlugin}) => {
            const [pluginFn, shikiHighlighter, opts] = rehypePlugin as any;
            highlighter = shikiHighlighter;
            shikiProcessor = createBaseProcessor()
                .use(pluginFn, shikiHighlighter, opts)
                .use(rehypeEditDiff, shikiHighlighter);
            currentProcessor = shikiProcessor;
            console.log('[shiki] loaded!');
            post(<ShikiLangsReadyMsg>{type: 'shiki-langs-ready'});
        })
        .catch(e => {
            console.error('[shiki] init failed', e);
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
    console.log('detected langs', detectedLangs, diffLangs);
    diffLangs?.forEach(l => detectedLangs.add(l));
    return detectedLangs;
}

export function parseMarkdown(seq: number, src: string, fast = false, isStreaming = false): HastRoot {
    const timeLabel = fast ? 'parse (fast)' : 'parse';
    console.time(timeLabel);
    const proc = fast ? fastBaseProcessor : currentProcessor;
    let tree: HastRoot = null;
    let mdastTree: Root = null;
    console.log(proc);
    try {
        // Reset the edit block ID counter before parsing
        resetForBubble(seq);
        mdastTree = proc.parse(src) as Root;
        tree = proc.runSync(mdastTree) as HastRoot;
    } catch (e) {
        console.error('parse failed', e);
        throw e;
    }

    // Check for symbol candidates from rehype plugin and perform lookup asynchronously (only in non-fast mode)
    if (!fast && !SKIP_SYMBOL_RESOLUTION) {
        const symbolCandidates = (tree.data as any)?.symbolCandidates as Set<string>;
        if (symbolCandidates && symbolCandidates.size > 0) {
            // Start symbol lookup asynchronously - use streaming-aware lookup
            setTimeout(() => {
                handleSymbolLookupWithContextRequestStreaming(symbolCandidates, tree, seq, post, isStreaming);
            }, 0);
        }
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

// Re-export function from symbol-lookup module with post function injection
export function handleSymbolLookupResponse(seq: number, results: Record<string, string>, contextId: string): void {
    handleSymbolLookupResponseInternal(seq, results, contextId, post);
}

// Re-export cache functions from symbol-lookup module
export { clearSymbolCache, clearContextCache };
