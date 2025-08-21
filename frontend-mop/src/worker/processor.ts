import type {Root as HastRoot} from 'hast';
import type {Parent, Root, RootContent} from 'mdast';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import remarkParse from 'remark-parse';
import remarkRehype from 'remark-rehype';
import { rehypeEditDiff } from './rehype/rehype-edit-diff';
import { rehypeSymbolLookup, enhanceSymbolCandidates } from './rehype/rehype-symbol-lookup';
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

// Symbol cache with upper limit
const SYMBOL_CACHE_LIMIT = 1000;
const symbolCache = new Map<string, {exists: boolean, fqn?: string | null}>();


export function createBaseProcessor(): Processor {
    return unified()
        .use(remarkParse)
        .data('micromarkExtensions', [gfmEditBlock()])
        .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
        .use(remarkGfm)
        .use(remarkBreaks)
        .use(remarkRehype, {allowDangerousHtml: true})
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
            shikiProcessor = createBaseProcessor()
                .use(pluginFn, shikiHighlighter, opts)
                .use(rehypeEditDiff, shikiHighlighter);

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
    const proc = fast ? fastBaseProcessor : currentProcessor;

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

    // Check for symbol candidates from rehype plugin and perform lookup (only in non-fast mode)
    if (!fast) {
        const symbolCandidates = (tree.data as any)?.symbolCandidates as Set<string>;
        if (symbolCandidates && symbolCandidates.size > 0) {
            // For now, request context ID when we need it for symbol lookup
            // This will be done through a sync call to the main thread
            handleSymbolLookupWithContextRequest(symbolCandidates, tree, seq);
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

function handleSymbolLookupWithContextRequest(symbols: Set<string>, tree: HastRoot, seq: number): void {
    // Store the tree and symbols, then immediately proceed with the lookup using a default context for now
    // In practice, the main thread should provide the context ID when making calls
    // For this implementation, let's use a simpler approach and assume the main thread will provide context ID
    const defaultContextId = 'main-context'; // Temporary solution
    handleSymbolLookup(symbols, tree, seq, defaultContextId);
}

function handleSymbolLookup(symbols: Set<string>, tree: HastRoot, seq: number, contextId: string): void {
    workerLog('info', `symbol-lookup request: ${Array.from(symbols).join(', ')} for context: ${contextId}`);
    try {
        const symbolArray = Array.from(symbols);

        // Check cache first and filter out already cached symbols (using context-specific keys)
        const cachedResults: Record<string, {exists: boolean, fqn?: string | null}> = {};
        const uncachedSymbols: string[] = [];

        for (const symbol of symbolArray) {
            const cacheKey = `${contextId}:${symbol}`;
            if (symbolCache.has(cacheKey)) {
                cachedResults[symbol] = symbolCache.get(cacheKey)!;
            } else {
                uncachedSymbols.push(symbol);
            }
        }

        // If we have cached results, enhance tree immediately
        if (Object.keys(cachedResults).length > 0) {
            workerLog('info', `Using cached results for ${Object.keys(cachedResults).length} symbols`);
            enhanceSymbolCandidates(tree, cachedResults);
        }

        // Only lookup uncached symbols
        if (uncachedSymbols.length > 0) {
            workerLog('info', `Looking up ${uncachedSymbols.length} uncached symbols`);
            // Store the pending lookup with the tree so we can enhance it later
            pendingSymbolLookups.set(seq, { symbols, tree });

            // Send request to main thread to handle JavaBridge communication
            post({
                type: 'symbol-lookup-request',
                symbols: uncachedSymbols,
                seq: seq,
                contextId: contextId
            });
        } else {
            workerLog('info', 'All symbols found in cache, no lookup needed');
            // All symbols were cached, send result immediately
            post({
                type: 'result',
                tree: tree,
                seq: seq
            });
        }
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

export function handleSymbolLookupResponse(seq: number, results: Record<string, {exists: boolean, fqn?: string | null}>, contextId: string): void {
    workerLog('info', `symbol-lookup-response processing: ${Object.keys(results).length} symbols for context: ${contextId}`);

    // Add results to cache with context-specific keys
    for (const [symbol, result] of Object.entries(results)) {
        // Implement simple cache eviction when limit reached
        if (symbolCache.size >= SYMBOL_CACHE_LIMIT) {
            // Remove first (oldest) entry
            const firstKey = symbolCache.keys().next().value;
            if (firstKey) {
                symbolCache.delete(firstKey);
                workerLog('debug', `Evicted symbol from cache: ${firstKey}`);
            }
        }
        const cacheKey = `${contextId}:${symbol}`;
        symbolCache.set(cacheKey, result);
    }
    workerLog('info', `Cached ${Object.keys(results).length} symbol results with context ${contextId}. Cache size: ${symbolCache.size}`);

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

// Context-specific cache functions
export function clearContextCache(contextId: string): void {
    const prefix = `${contextId}:`;
    let clearedCount = 0;
    for (const key of symbolCache.keys()) {
        if (key.startsWith(prefix)) {
            symbolCache.delete(key);
            clearedCount++;
        }
    }
    workerLog('info', `Cleared ${clearedCount} entries for context: ${contextId}`);
}

// Export function to clear symbol cache (called on worker reset)
export function clearSymbolCache(): void {
    symbolCache.clear();
    workerLog('info', 'Symbol cache cleared');
}
