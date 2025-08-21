import type {Root as HastRoot} from 'hast';
import type {Parent, Root, RootContent} from 'mdast';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import { rehypeEditDiff } from './rehype/rehype-edit-diff';
import { rehypeSymbolLookup, enhanceSymbolCandidates } from './rehype/rehype-symbol-lookup';
import { rehypeCodeLogger } from './rehype/rehype-code-logger';
import type {HighlighterCore} from 'shiki/core';
import {type Processor, unified} from 'unified';
import {visit} from 'unist-util-visit';
import type {Test} from 'unist-util-visit';
import {editBlockFromMarkdown, gfmEditBlock} from '../lib/micromark-edit-block';
import type {OutboundFromWorker, ShikiLangsReadyMsg} from './shared';
import {ensureLang} from './shiki/ensure-langs';
import {shikiPluginPromise} from './shiki/shiki-plugin';
import { resetForBubble } from '../lib/edit-block/id-generator';
import { createLogger } from '../lib/logging';


const log = createLogger('md-worker');

function post(msg: OutboundFromWorker) {
    self.postMessage(msg);
}

// Worker logging helper
function workerLog(level: 'info' | 'warn' | 'error' | 'debug', message: string) {
    self.postMessage({ type: 'worker-log', level, message });
}

// Track pending symbol lookups
const pendingSymbolLookups = new Map<number, {symbols: Set<string>, tree: HastRoot}>();


export function createBaseProcessor(): Processor {
    return unified()
        .use(remarkParse)
        .data('micromarkExtensions', [gfmEditBlock()])
        .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
        .use(remarkGfm)
        .use(remarkBreaks)
        // .use(remarkRehype, {allowDangerousHtml: true}) as any;
        .use(remarkRehype, {allowDangerousHtml: true})
        .use(rehypeCodeLogger)
        .use(rehypeSymbolLookup) as any;
}
// processors
let baseProcessor: Processor = createBaseProcessor();
let shikiProcessor: Processor = null;
let currentProcessor: Processor = baseProcessor;
let processorInitialized = false;
let initializationPromise: Promise<void> | null = null;
// shiki-highlighter
export let highlighter: HighlighterCore | null = null;

export function initProcessor(): Promise<void> {
    if (initializationPromise) {
        return initializationPromise; // Return existing promise
    }

    // Asynchronously initialize Shiki and create a new processor with it.
    log.info('shiki loading lib...');
    initializationPromise = shikiPluginPromise
        .then(({rehypePlugin}) => {
            const [pluginFn, shikiHighlighter, opts] = rehypePlugin as any;
            highlighter = shikiHighlighter;
            shikiProcessor = unified()
                .use(remarkParse)
                .data('micromarkExtensions', [gfmEditBlock()])
                .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
                .use(remarkGfm)
                .use(remarkBreaks)
                .use(remarkRehype, {allowDangerousHtml: true})
                .use(pluginFn, shikiHighlighter, opts)
                // .use(rehypeEditDiff, shikiHighlighter) as any;
                .use(rehypeEditDiff, shikiHighlighter)
                .use(rehypeCodeLogger)
                .use(rehypeSymbolLookup) as any;

            // Atomic update: set processor and flag together
            currentProcessor = shikiProcessor;
            processorInitialized = true;

            console.log('[shiki] loaded!');
            post(<ShikiLangsReadyMsg>{type: 'shiki-langs-ready'});
        })
        .catch(e => {
            log.error('Shiki init failed', e);
            processorInitialized = false; // Mark as failed
            throw e;
        });

    return initializationPromise;
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
    log.info('detected langs', detectedLangs, diffLangs);
    diffLangs?.forEach(l => detectedLangs.add(l));
    return detectedLangs;
}

export async function parseMarkdown(seq: number, src: string, fast = false): Promise<HastRoot> {
    // Wait for shiki processor when not using fast mode
    if (!fast && !processorInitialized && initializationPromise) {
        workerLog('info', `[markdown-worker] Waiting for shiki processor initialization (seq=${seq})`);
        await initializationPromise;
        workerLog('info', `[markdown-worker] Shiki processor ready, proceeding with parsing (seq=${seq})`);
    }

    const timeLabel = fast ? 'parse (fast)' : 'parse';
    console.time(timeLabel);
    const proc = fast ? baseProcessor : currentProcessor;

    // Log which processor is being used
    const processorType = fast ? 'baseProcessor' : (currentProcessor === shikiProcessor ? 'shikiProcessor' : 'baseProcessor');
    workerLog('info', `[markdown-worker] Using ${processorType} for parsing (fast=${fast})`);

    // Log if the source contains code elements
    const hasInlineCode = src.includes('`');
    const hasCodeBlock = src.includes('```');
    workerLog('info', `[markdown-worker] Source contains: ${hasInlineCode ? 'inline code' : 'no inline'}, ${hasCodeBlock ? 'code blocks' : 'no blocks'}`);
    let tree: HastRoot = null;
    let mdastTree: Root = null;
    try {
        // Reset the edit block ID counter before parsing
        resetForBubble(seq);
        mdastTree = proc.parse(src) as Root;
        tree = proc.runSync(mdastTree) as HastRoot;
    } catch (e) {
        log.error('parse failed', e);
        throw e;
    }

    // Check for symbol candidates from rehype plugin and perform lookup
    const symbolCandidates = (tree.data as any)?.symbolCandidates as Set<string>;

    if (symbolCandidates && symbolCandidates.size > 0) {
        handleSymbolLookup(symbolCandidates, tree, seq);
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

function handleSymbolLookup(symbols: Set<string>, tree: HastRoot, seq: number): void {
    workerLog('info', `symbol-lookup request: ${Array.from(symbols).join(', ')}`);
    try {
        const symbolArray = Array.from(symbols);

        // Store the pending lookup with the tree so we can enhance it later
        pendingSymbolLookups.set(seq, { symbols, tree });

        // Send request to main thread to handle JavaBridge communication
        post({
            type: 'symbol-lookup-request',
            symbols: symbolArray,
            seq: seq
        });
    } catch (e) {
        log.warn('Error during symbol lookup request:', e);
    }
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

export function handleSymbolLookupResponse(seq: number, results: Record<string, {exists: boolean, fqn?: string | null}>): void {
    workerLog('info', `symbol-lookup-response processing: ${Object.keys(results).length} symbols`);
    const pending = pendingSymbolLookups.get(seq);
    if (pending) {
        workerLog('info', `Found pending lookup for seq ${seq}, enhancing tree...`);
        // Enhance the tree with symbol information
        enhanceSymbolCandidates(pending.tree, results);
        workerLog('info', `Tree enhanced, sending result back to main thread...`);

        // Send the enhanced tree back to main thread to trigger DOM update
        post({
            type: 'result',
            tree: pending.tree,
            seq: seq
        });
        workerLog('info', `Enhanced tree sent to main thread for seq ${seq}`);

        // Clean up the pending lookup
        pendingSymbolLookups.delete(seq);
        workerLog('info', `Cleaned up pending lookup for seq ${seq}`);
    } else {
        workerLog('warn', `No pending lookup found for seq ${seq}`);
    }
}
