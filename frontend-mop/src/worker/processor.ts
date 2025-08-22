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
import { createWorkerLogger } from '../lib/logging';


// Create tagged logger for symbol cache debugging
const cacheLog = createWorkerLogger('symbol-cache');

function post(msg: OutboundFromWorker) {
    self.postMessage(msg);
}


// Track pending symbol lookups
const pendingSymbolLookups = new Map<number, {symbols: Set<string>, tree: HastRoot}>();

// Symbol cache with upper limit - optimized format (only known symbols)
const SYMBOL_CACHE_LIMIT = 1000;
const symbolCache = new Map<string, string>();

// Cache debugging statistics
let cacheStats = {
    requests: 0,
    hits: 0,
    misses: 0,
    evictions: 0,
    totalSymbolsProcessed: 0
};


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
// shiki-highlighter
export let highlighter: HighlighterCore | null = null;

export function initProcessor() {
    // Asynchronously initialize Shiki and create a new processor with it.
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

export function parseMarkdown(seq: number, src: string, fast = false): HastRoot {
    const timeLabel = fast ? 'parse (fast)' : 'parse';
    console.time(timeLabel);
    const proc = fast ? fastBaseProcessor : currentProcessor;
    let tree: HastRoot = null;
    let mdastTree: Root = null;
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
    if (!fast) {
        const symbolCandidates = (tree.data as any)?.symbolCandidates as Set<string>;
        if (symbolCandidates && symbolCandidates.size > 0) {
            // Start symbol lookup asynchronously - don't block parsing
            setTimeout(() => {
                handleSymbolLookupWithContextRequest(symbolCandidates, tree, seq);
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

function handleSymbolLookupWithContextRequest(symbols: Set<string>, tree: HastRoot, seq: number): void {
    // Store the tree and symbols, then immediately proceed with the lookup using a default context for now
    // In practice, the main thread should provide the context ID when making calls
    // For this implementation, let's use a simpler approach and assume the main thread will provide context ID
    const defaultContextId = 'main-context'; // Temporary solution
    cacheLog.debug(`Using default context ID '${defaultContextId}' for ${symbols.size} symbols in seq ${seq}`);
    handleSymbolLookup(symbols, tree, seq, defaultContextId);
}

function handleSymbolLookup(symbols: Set<string>, tree: HastRoot, seq: number, contextId: string): void {
    cacheStats.requests++;
    cacheStats.totalSymbolsProcessed += symbols.size;

    cacheLog.info(`Lookup request seq:${seq} context:'${contextId}' symbols:${symbols.size} [${Array.from(symbols).join(', ')}]`);

    try {
        const symbolArray = Array.from(symbols);

        // Check cache first and filter out already cached symbols (using context-specific keys)
        const cachedResults: Record<string, string> = {};
        const uncachedSymbols: string[] = [];

        for (const symbol of symbolArray) {
            const cacheKey = `${contextId}:${symbol}`;
            cacheLog.debug(`Checking cache for key: '${cacheKey}'`);

            if (symbolCache.has(cacheKey)) {
                const cachedFqn = symbolCache.get(cacheKey)!;
                cachedResults[symbol] = cachedFqn;
                cacheStats.hits++;
                cacheLog.debug(`Cache HIT for '${symbol}' -> fqn:${cachedFqn}`);
            } else {
                uncachedSymbols.push(symbol);
                cacheStats.misses++;
                cacheLog.debug(`Cache MISS for '${symbol}' (key: '${cacheKey}')`);
            }
        }

        // Log cache statistics every 10 requests
        if (cacheStats.requests % 10 === 0) {
            const hitRate = ((cacheStats.hits / (cacheStats.hits + cacheStats.misses)) * 100).toFixed(1);
            cacheLog.info(`Cache Stats: requests:${cacheStats.requests} hits:${cacheStats.hits} misses:${cacheStats.misses} hit-rate:${hitRate}% size:${symbolCache.size}/${SYMBOL_CACHE_LIMIT} evictions:${cacheStats.evictions}`);
        }

        // If we have cached results, enhance tree with them
        if (Object.keys(cachedResults).length > 0) {
            cacheLog.info(`Using cached results for ${Object.keys(cachedResults).length} symbols`);
            enhanceSymbolCandidates(tree, cachedResults);
        }

        // Only lookup uncached symbols
        if (uncachedSymbols.length > 0) {
            cacheLog.info(`Need to lookup ${uncachedSymbols.length} uncached symbols: [${uncachedSymbols.join(', ')}]`);
            // Store the pending lookup with the tree so we can enhance it later
            pendingSymbolLookups.set(seq, { symbols, tree });
            cacheLog.debug(`Stored pending lookup for seq:${seq}`);

            // Send request to main thread to handle JavaBridge communication
            post({
                type: 'symbol-lookup-request',
                symbols: uncachedSymbols,
                seq: seq,
                contextId: contextId
            });
            cacheLog.debug(`Sent lookup request to main thread for seq:${seq}`);
        } else {
            cacheLog.info(`All ${symbolArray.length} symbols found in cache, no lookup needed`);
            // All symbols were cached, send result with enhanced tree
            post({
                type: 'result',
                tree: tree,
                seq: seq
            });
            cacheLog.debug(`Sent cached result to main thread for seq:${seq}`);
        }
    } catch (e) {
        cacheLog.error(`Error during symbol lookup request seq:${seq}:`, e);
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

export function handleSymbolLookupResponse(seq: number, results: Record<string, string>, contextId: string): void {
    cacheLog.info(`Response processing seq:${seq} context:'${contextId}' results:${Object.keys(results).length}`);

    // Add results to cache with context-specific keys
    let newEntriesAdded = 0;
    for (const [symbol, fqn] of Object.entries(results)) {
        // Implement simple cache eviction when limit reached
        if (symbolCache.size >= SYMBOL_CACHE_LIMIT) {
            // Remove first (oldest) entry
            const firstKey = symbolCache.keys().next().value;
            if (firstKey) {
                symbolCache.delete(firstKey);
                cacheStats.evictions++;
                cacheLog.debug(`Evicted symbol from cache: '${firstKey}' (limit:${SYMBOL_CACHE_LIMIT})`);
            }
        }
        const cacheKey = `${contextId}:${symbol}`;
        symbolCache.set(cacheKey, fqn);
        newEntriesAdded++;
        cacheLog.debug(`Cached '${symbol}' (key:'${cacheKey}') -> fqn:${fqn}`);
    }
    cacheLog.info(`Cached ${newEntriesAdded} new entries. Total cache size: ${symbolCache.size}/${SYMBOL_CACHE_LIMIT}`);

    const pending = pendingSymbolLookups.get(seq);
    if (pending) {
        cacheLog.debug(`Found pending lookup for seq:${seq}, enhancing tree...`);

        // Enhance the tree with symbol information using optimized format
        enhanceSymbolCandidates(pending.tree, results);
        cacheLog.debug(`Tree enhanced for seq:${seq}, sending result back to main thread...`);

        // Send the enhanced tree back to main thread to trigger DOM update
        post({
            type: 'result',
            tree: pending.tree,
            seq: seq
        });
        cacheLog.info(`Enhanced tree sent to main thread for seq:${seq}`);

        // Clean up the pending lookup
        pendingSymbolLookups.delete(seq);
        cacheLog.debug(`Cleaned up pending lookup for seq:${seq}`);
    } else {
        cacheLog.warn(`No pending lookup found for seq:${seq} - possible duplicate response or cleanup issue`);
        cacheLog.debug(`Current pending lookups: [${Array.from(pendingSymbolLookups.keys()).join(', ')}]`);
    }
}

// Context-specific cache functions
export function clearContextCache(contextId: string): void {
    const prefix = `${contextId}:`;
    let clearedCount = 0;
    const keysToDelete: string[] = [];

    // Collect keys to delete (avoid modifying during iteration)
    for (const key of symbolCache.keys()) {
        if (key.startsWith(prefix)) {
            keysToDelete.push(key);
        }
    }

    // Delete collected keys
    for (const key of keysToDelete) {
        symbolCache.delete(key);
        clearedCount++;
    }

    cacheLog.info(`Cleared ${clearedCount} entries for context: '${contextId}'. Remaining cache size: ${symbolCache.size}`);

    // Reset stats if cache is now empty
    if (symbolCache.size === 0) {
        cacheStats = { requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0 };
        cacheLog.debug('Cache stats reset after context clear');
    }
}

// Export function to clear symbol cache (called on worker reset)
export function clearSymbolCache(): void {
    const previousSize = symbolCache.size;
    symbolCache.clear();

    // Reset all statistics
    cacheStats = { requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0 };

    cacheLog.info(`Symbol cache completely cleared. Removed ${previousSize} entries. Stats reset.`);
}
