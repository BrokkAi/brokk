import type {Root as HastRoot} from 'hast';
import { enhanceSymbolCandidates } from './rehype/rehype-symbol-lookup';
import type {OutboundFromWorker} from './shared';
import { createWorkerLogger } from '../lib/logging';

// Debug flag for cache logs - can be controlled externally
export let debugCacheLogs = true;

export function setDebugCacheLogs(enabled: boolean): void {
    debugCacheLogs = enabled;
}

// Create tagged logger for symbol cache debugging
const cacheLog = createWorkerLogger('symbol-cache');

// Helper function to conditionally log debug messages
function debugLog(message: string, ...args: any[]): void {
    if (debugCacheLogs) {
        cacheLog.debug(message, ...args);
    }
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

// Streaming symbol throttle for progressive enhancement during streaming
class StreamingSymbolThrottle {
    private pendingSymbols = new Set<string>();
    private pendingTrees = new Map<number, HastRoot>();
    private throttleTimer: number | null = null;
    private readonly THROTTLE_DELAY = 100; // ms - resolve every 100ms during streaming
    private readonly BATCH_SIZE = 5; // Small batches to avoid blocking
    private postMessage: ((msg: OutboundFromWorker) => void) | null = null;
    private contextId = 'main-context';

    setPostMessage(postFn: (msg: OutboundFromWorker) => void) {
        this.postMessage = postFn;
    }

    queueStreamingSymbols(symbols: Set<string>, tree: HastRoot, seq: number, contextId: string) {
        debugLog(`Queueing ${symbols.size} streaming symbols for seq:${seq}`);

        // Add symbols to pending queue
        symbols.forEach(s => this.pendingSymbols.add(s));
        this.pendingTrees.set(seq, tree);
        this.contextId = contextId;

        // Schedule batch processing if not already scheduled
        if (!this.throttleTimer && this.pendingSymbols.size > 0) {
            this.throttleTimer = setTimeout(() => {
                this.processBatch();
                this.throttleTimer = null;
            }, this.THROTTLE_DELAY);
        }

        // Force processing if batch gets large to prevent memory buildup
        if (this.pendingSymbols.size >= this.BATCH_SIZE * 3) {
            this.processBatch();
        }
    }

    private processBatch() {
        if (!this.postMessage || this.pendingSymbols.size === 0) {
            return;
        }

        // Take small batch of symbols
        const batch = Array.from(this.pendingSymbols).slice(0, this.BATCH_SIZE);
        debugLog(`Processing streaming batch of ${batch.length} symbols: [${batch.join(', ')}]`);

        // Remove from pending queue
        batch.forEach(s => this.pendingSymbols.delete(s));

        // Process the batch using existing lookup mechanism
        handleSymbolLookup(new Set(batch), this.getLatestTree(), this.getLatestSeq(), this.contextId, this.postMessage);

        // Continue processing if more symbols pending
        if (this.pendingSymbols.size > 0) {
            this.throttleTimer = setTimeout(() => {
                this.processBatch();
                this.throttleTimer = null;
            }, this.THROTTLE_DELAY);
        }
    }

    private getLatestTree(): HastRoot {
        // Get the most recent tree from pending trees
        const seqs = Array.from(this.pendingTrees.keys()).sort((a, b) => b - a);
        return seqs.length > 0 ? this.pendingTrees.get(seqs[0])! : {} as HastRoot;
    }

    private getLatestSeq(): number {
        const seqs = Array.from(this.pendingTrees.keys()).sort((a, b) => b - a);
        return seqs.length > 0 ? seqs[0] : 0;
    }

    clearPendingSymbols() {
        this.pendingSymbols.clear();
        this.pendingTrees.clear();
        if (this.throttleTimer) {
            clearTimeout(this.throttleTimer);
            this.throttleTimer = null;
        }
        debugLog('Cleared all pending streaming symbols');
    }
}

// Global streaming throttle instance
const streamingThrottle = new StreamingSymbolThrottle();

export function handleSymbolLookupWithContextRequest(symbols: Set<string>, tree: HastRoot, seq: number, postMessage: (msg: OutboundFromWorker) => void): void {
    // Store the tree and symbols, then immediately proceed with the lookup using a default context for now
    // In practice, the main thread should provide the context ID when making calls
    // For this implementation, let's use a simpler approach and assume the main thread will provide context ID
    const defaultContextId = 'main-context'; // Temporary solution
    debugLog(`Using default context ID '${defaultContextId}' for ${symbols.size} symbols in seq ${seq}`);
    handleSymbolLookup(symbols, tree, seq, defaultContextId, postMessage);
}

// New streaming-aware symbol lookup function
export function handleSymbolLookupWithContextRequestStreaming(symbols: Set<string>, tree: HastRoot, seq: number, postMessage: (msg: OutboundFromWorker) => void, isStreaming: boolean = false): void {
    const defaultContextId = 'main-context';
    streamingThrottle.setPostMessage(postMessage);

    if (isStreaming) {
        // During streaming: queue symbols for throttled processing
        debugLog(`Streaming mode: queueing ${symbols.size} symbols for seq:${seq}`);
        streamingThrottle.queueStreamingSymbols(symbols, tree, seq, defaultContextId);
    } else {
        // Not streaming: immediate lookup
        debugLog(`Non-streaming mode: immediate lookup for ${symbols.size} symbols in seq ${seq}`);
        handleSymbolLookup(symbols, tree, seq, defaultContextId, postMessage);
    }
}

export function handleSymbolLookup(symbols: Set<string>, tree: HastRoot, seq: number, contextId: string, postMessage: (msg: OutboundFromWorker) => void): void {
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
            debugLog(`Checking cache for key: '${cacheKey}'`);

            if (symbolCache.has(cacheKey)) {
                const cachedFqn = symbolCache.get(cacheKey)!;
                cachedResults[symbol] = cachedFqn;
                cacheStats.hits++;
                debugLog(`Cache HIT for '${symbol}' -> fqn:${cachedFqn}`);
            } else {
                uncachedSymbols.push(symbol);
                cacheStats.misses++;
                debugLog(`Cache MISS for '${symbol}' (key: '${cacheKey}')`);
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
            debugLog(`Stored pending lookup for seq:${seq}`);

            // Send request to main thread to handle JavaBridge communication
            postMessage({
                type: 'symbol-lookup-request',
                symbols: uncachedSymbols,
                seq: seq,
                contextId: contextId
            });
            debugLog(`Sent lookup request to main thread for seq:${seq}`);
        } else {
            cacheLog.info(`All ${symbolArray.length} symbols found in cache, no lookup needed`);
            // All symbols were cached, send result with enhanced tree
            postMessage({
                type: 'result',
                tree: JSON.parse(JSON.stringify(tree)),
                seq: seq
            });
            debugLog(`Sent cached result to main thread for seq:${seq}`);
        }
    } catch (e) {
        cacheLog.error(`Error during symbol lookup request seq:${seq}:`, e);
    }
}

export function handleSymbolLookupResponse(seq: number, results: Record<string, string>, contextId: string, postMessage: (msg: OutboundFromWorker) => void): void {
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
                debugLog(`Evicted symbol from cache: '${firstKey}' (limit:${SYMBOL_CACHE_LIMIT})`);
            }
        }
        const cacheKey = `${contextId}:${symbol}`;
        symbolCache.set(cacheKey, fqn);
        newEntriesAdded++;
        debugLog(`Cached '${symbol}' (key:'${cacheKey}') -> fqn:${fqn}`);
    }
    cacheLog.info(`Cached ${newEntriesAdded} new entries. Total cache size: ${symbolCache.size}/${SYMBOL_CACHE_LIMIT}`);

    const pending = pendingSymbolLookups.get(seq);
    if (pending) {
        debugLog(`Found pending lookup for seq:${seq}, enhancing tree...`);

        // Enhance the tree with symbol information using optimized format
        enhanceSymbolCandidates(pending.tree, results);
        debugLog(`Tree enhanced for seq:${seq}, sending result back to main thread...`);

        // Send the enhanced tree back to main thread to trigger DOM update
        postMessage({
            type: 'result',
            tree: JSON.parse(JSON.stringify(pending.tree)),
            seq: seq
        });
        cacheLog.info(`Enhanced tree sent to main thread for seq:${seq}`);

        // Clean up the pending lookup
        pendingSymbolLookups.delete(seq);
        debugLog(`Cleaned up pending lookup for seq:${seq}`);
    } else {
        cacheLog.warn(`No pending lookup found for seq:${seq} - possible duplicate response or cleanup issue`);
        debugLog(`Current pending lookups: [${Array.from(pendingSymbolLookups.keys()).join(', ')}]`);
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
        debugLog('Cache stats reset after context clear');
    }
}

// Export function to clear symbol cache (called on worker reset)
export function clearSymbolCache(): void {
    const previousSize = symbolCache.size;
    symbolCache.clear();

    // Clear streaming throttle as well
    streamingThrottle.clearPendingSymbols();

    // Reset all statistics
    cacheStats = { requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0 };

    cacheLog.info(`Symbol cache completely cleared. Removed ${previousSize} entries. Stats reset.`);
}

// Getter for cache stats (for debugging/monitoring)
export function getCacheStats() {
    return { ...cacheStats };
}

// Getter for cache size (for debugging/monitoring)
export function getCacheSize(): number {
    return symbolCache.size;
}