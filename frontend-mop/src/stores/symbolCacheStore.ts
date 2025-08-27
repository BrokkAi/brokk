import {writable} from 'svelte/store';
import {createLogger} from '../lib/logging';

const log = createLogger('symbol-cache-store');

// Add request deduplication infrastructure
const inflightRequests = new Map<string, Promise<void>>();

// Add sequence tracking for validation
let currentSequence = 0;
const contextSequences = new Map<string, number>();
// Track context switch sequences separately for proper validation
const contextSwitchSequences = new Map<string, number>();

// Adaptive batch coordination for component requests
const IMMEDIATE_THRESHOLD = 100; // ms - if no requests for 100ms, next one is immediate
const BATCH_DELAY = 15; // ms - reduced delay for subsequent requests
let batchTimer: number | null = null;
const pendingBatchRequests = new Map<string, Set<string>>();
// Track last request time per context to determine if we should batch
const lastRequestTime = new Map<string, number>();

// Symbol cache entry with resolution status
export interface SymbolCacheEntry {
    fqn?: string;
    status: 'pending' | 'resolved';
    contextId: string;
    timestamp: number;
    accessCount: number;
}

// Create reactive store for symbol cache
export const symbolCacheStore = writable<Map<string, SymbolCacheEntry>>(new Map());

// Cache configuration
const SYMBOL_CACHE_LIMIT = 1000;

// Cache statistics for debugging
let cacheStats = {
    requests: 0,
    hits: 0,
    misses: 0,
    evictions: 0,
    totalSymbolsProcessed: 0,
    responses: 0,
    lastUpdate: 0,
    symbolsFound: 0,
    symbolsNotFound: 0
};

// Track sequence for validation
function getNextSequence(contextId: string): number {
    const seq = ++currentSequence;
    contextSequences.set(contextId, seq);
    log.debug(`Generated sequence ${seq} for context ${contextId}`);
    return seq;
}

function isValidSequence(contextId: string, seq: number): boolean {
    // Get the minimum acceptable sequence for this context (from last context switch)
    const minValidSeq = contextSwitchSequences.get(contextId) || 0;

    // Accept any sequence >= the last context switch sequence
    // This allows out-of-order responses from concurrent requests while
    // still rejecting truly stale responses from before context switches
    const isValid = seq >= minValidSeq;

    if (!isValid) {
        log.debug(`Stale sequence ${seq} for context ${contextId}, min valid: ${minValidSeq}`);
    } else if (seq < (contextSequences.get(contextId) || 0)) {
        log.debug(`Out-of-order sequence ${seq} for context ${contextId}, but still valid (>= ${minValidSeq})`);
    }

    return isValid;
}

function shouldProcessImmediately(contextId: string): boolean {
    const now = Date.now();
    const lastRequest = lastRequestTime.get(contextId) || 0;
    const timeSinceLastRequest = now - lastRequest;

    // Process immediately if:
    // 1. This is the first request for this context, OR
    // 2. Enough time has passed since the last request (indicates user is not rapidly typing)
    const immediate = timeSinceLastRequest >= IMMEDIATE_THRESHOLD;

    log.debug(`Should process immediately for ${contextId}: ${immediate} (${timeSinceLastRequest}ms since last request)`);
    return immediate;
}

function addToBatch(contextId: string, symbol: string): void {
    const now = Date.now();

    // Update last request time for this context
    lastRequestTime.set(contextId, now);

    if (!pendingBatchRequests.has(contextId)) {
        pendingBatchRequests.set(contextId, new Set());
    }

    pendingBatchRequests.get(contextId)!.add(symbol);

    // Determine if we should process immediately or batch
    if (shouldProcessImmediately(contextId) && batchTimer === null) {
        // Process immediately for first request or after inactivity
        log.debug(`Processing ${symbol} immediately for context ${contextId}`);
        processBatches(true); // true = immediate processing
    } else {
        // Schedule batch processing with delay for rapid follow-up requests
        if (batchTimer === null) {
            log.debug(`Scheduling batched processing in ${BATCH_DELAY}ms for context ${contextId}`);
            batchTimer = window.setTimeout(() => {
                processBatches(false); // false = batched processing
                batchTimer = null;
            }, BATCH_DELAY);
        } else {
            log.debug(`Added ${symbol} to existing batch for context ${contextId}`);
        }
    }
}

function processBatches(immediate: boolean = false): void {
    if (immediate) {
        log.debug('Processing batches immediately');
    }
    for (const [contextId, symbols] of pendingBatchRequests.entries()) {
        if (symbols.size > 0) {
            const symbolArray = Array.from(symbols);
            log.debug(`Processing batch for context ${contextId}: ${symbolArray.length} symbols`);

            // Make single batched request
            const sequence = getNextSequence(contextId);
            if (window.javaBridge?.lookupSymbolsAsync) {
                window.javaBridge.lookupSymbolsAsync(
                    JSON.stringify(symbolArray),
                    sequence,
                    contextId
                );
            }

            // Mark all symbols as pending
            symbolCacheStore.update(cache => {
                const newCache = new Map(cache);
                for (const symbol of symbolArray) {
                    const cacheKey = `${contextId}:${symbol}`;
                    if (!newCache.has(cacheKey) || newCache.get(cacheKey)?.status !== 'resolved') {
                        const pendingEntry: SymbolCacheEntry = {
                            status: 'pending',
                            contextId: contextId,
                            timestamp: Date.now(),
                            accessCount: 1
                        };
                        newCache.set(cacheKey, pendingEntry);
                    }
                }
                return newCache;
            });
        }
    }

    // Clear all batches
    pendingBatchRequests.clear();
    log.debug('Batch processing completed');
}

/**
 * Request symbol resolution for a symbol within a context
 */
export function requestSymbolResolution(symbol: string, contextId: string = 'main-context'): Promise<void> {
    const cacheKey = `${contextId}:${symbol}`;

    log.debug(`Symbol lookup request: ${symbol} (context: ${contextId})`);

    // Check if request already in flight
    if (inflightRequests.has(cacheKey)) {
        log.debug(`Request already in flight for: ${cacheKey}`);
        return inflightRequests.get(cacheKey)!;
    }

    // Atomic check-and-set operation
    const requestPromise = performAtomicSymbolLookup(symbol, contextId, cacheKey);

    // Track in-flight request
    inflightRequests.set(cacheKey, requestPromise);

    // Clean up when done
    requestPromise.finally(() => {
        inflightRequests.delete(cacheKey);
        log.debug(`Cleaned up in-flight request: ${cacheKey}`);
    });

    return requestPromise;
}

/**
 * Atomic symbol lookup with proper race condition handling
 */
async function performAtomicSymbolLookup(symbol: string, contextId: string, cacheKey: string): Promise<void> {
    return new Promise<void>((resolve) => {
        symbolCacheStore.update(cache => {
            // Check if already resolved while waiting
            const existing = cache.get(cacheKey);
            if (existing?.status === 'resolved') {
                log.debug(`Symbol already resolved while waiting: ${symbol}`);
                cacheStats.hits++;
                resolve();
                return cache; // No changes needed
            }

            // Set to pending state
            const newCache = new Map(cache);
            const pendingEntry: SymbolCacheEntry = {
                status: 'pending',
                contextId: contextId,
                timestamp: Date.now(),
                accessCount: 1
            };
            newCache.set(cacheKey, pendingEntry);

            // Update stats
            cacheStats.requests++;
            cacheStats.misses++;
            cacheStats.totalSymbolsProcessed++;


            // Add to batch instead of making immediate call
            log.debug(`Adding symbol to batch: ${symbol}`);
            if (window.javaBridge?.lookupSymbolsAsync) {
                addToBatch(contextId, symbol);
            } else {
                log.warn('Java bridge not available for symbol lookup');
                // Set to resolved with no FQN to prevent retry loops
                setTimeout(() => {
                    symbolCacheStore.update(cache => {
                        const updatedCache = new Map(cache);
                        const errorEntry: SymbolCacheEntry = {
                            status: 'resolved',
                            contextId: contextId,
                            timestamp: Date.now(),
                            accessCount: 1
                        };
                        updatedCache.set(cacheKey, errorEntry);
                        return updatedCache;
                    });
                    resolve();
                }, 0);
            }

            resolve();
            return newCache;
        });
    });
}

/**
 * Handle symbol resolution response from Java bridge
 * @param results - Map of symbol names to their FQNs (or undefined for not found)
 * @param seqOrContextId - Either sequence number (legacy) or contextId (reactive)
 * @param contextId - Context ID (when sequence is provided)
 */
export function onSymbolResolutionResponse(results: Record<string, string>, seqOrContextId: number | string = 'main-context', contextId?: string): void {
    // Handle both signatures: (results, contextId) and (results, seq, contextId)
    let actualContextId: string;
    let sequence: number | null = null;

    if (typeof seqOrContextId === 'string') {
        // Legacy signature: (results, contextId)
        actualContextId = seqOrContextId;
    } else {
        // New signature: (results, seq, contextId)
        sequence = seqOrContextId;
        actualContextId = contextId || 'main-context';

        // Validate sequence if provided
        if (!isValidSequence(actualContextId, sequence)) {
            log.debug(`Ignoring stale response seq=${sequence} for context=${actualContextId}`);
            return; // Discard stale response
        }
    }

    log.debug(`Processing symbol resolution response for context: ${actualContextId}${sequence ? ` (seq: ${sequence})` : ''}`);
    log.debug(`Response contains ${Object.keys(results).length} symbol results`);

    if (Object.keys(results).length === 0) {
        log.debug('Empty results received');
        return;
    }

    symbolCacheStore.update(cache => {
        const newCache = new Map(cache);
        let updatedCount = 0;

        // Handle cache size limit with eviction
        const totalNewEntries = Object.keys(results).length;
        const currentSize = newCache.size;
        if (currentSize + totalNewEntries > SYMBOL_CACHE_LIMIT) {
            const toEvict = (currentSize + totalNewEntries) - SYMBOL_CACHE_LIMIT;
            const keysToDelete = Array.from(newCache.keys()).slice(0, toEvict);

            keysToDelete.forEach(key => {
                newCache.delete(key);
                cacheStats.evictions++;
            });

            log.debug(`Evicted ${keysToDelete.length} entries to stay under limit ${SYMBOL_CACHE_LIMIT}`);
        }

        // Update cache with resolved symbols
        for (const [symbol, fqn] of Object.entries(results)) {
            const cacheKey = `${actualContextId}:${symbol}`;
            const resolvedEntry: SymbolCacheEntry = {
                status: 'resolved',
                fqn: fqn,
                contextId: actualContextId,
                timestamp: Date.now(),
                accessCount: 1
            };
            newCache.set(cacheKey, resolvedEntry);
            updatedCount++;
            log.debug(`Cached '${symbol}' (key:'${cacheKey}') -> fqn:${fqn}`);
        }

        // Handle symbols that were requested but not resolved (don't exist)
        // Find all pending symbols for this context that weren't in the results
        let notFoundCount = 0;
        for (const [key, entry] of newCache.entries()) {
            if (entry.status === 'pending' && key.startsWith(actualContextId + ':')) {
                const symbolName = key.substring((actualContextId + ':').length);
                if (!results.hasOwnProperty(symbolName)) {
                    // Symbol was requested but not found in results - mark as resolved with no fqn
                    const notFoundEntry: SymbolCacheEntry = {
                        status: 'resolved',
                        contextId: actualContextId,
                        timestamp: Date.now(),
                        accessCount: 1
                        // Deliberately no fqn property - this indicates symbol doesn't exist
                    };
                    newCache.set(key, notFoundEntry);
                    notFoundCount++;
                    log.debug(`Symbol '${symbolName}' not found - cached as resolved without fqn`);
                }
            }
        }

        if (notFoundCount > 0) {
            log.debug(`Marked ${notFoundCount} symbols as not found`);
        }

        // Check for any pending symbols that weren't resolved
        let pendingCount = 0;
        for (const [key, entry] of newCache.entries()) {
            if (entry.status === 'pending' && key.startsWith(actualContextId + ':')) {
                pendingCount++;
                log.debug(`Still pending: ${key}`);
            }
        }

        log.debug(`Cache update complete: Updated ${updatedCount} symbols. ${pendingCount} still pending. Total size: ${newCache.size}/${SYMBOL_CACHE_LIMIT}`);

        // Update cache stats
        cacheStats.responses++;
        cacheStats.lastUpdate = Date.now();
        cacheStats.symbolsFound += updatedCount;
        cacheStats.symbolsNotFound += notFoundCount;


        // Log cache statistics periodically
        if (cacheStats.requests % 10 === 0) {
            const hitRate = ((cacheStats.hits / (cacheStats.hits + cacheStats.misses)) * 100).toFixed(1);
            log.debug(`Cache stats: requests:${cacheStats.requests} hits:${cacheStats.hits} misses:${cacheStats.misses} hit-rate:${hitRate}% size:${newCache.size}/${SYMBOL_CACHE_LIMIT} evictions:${cacheStats.evictions}`);
        }

        return newCache;
    });
}

/**
 * Get symbol cache entry for a given symbol and context
 */
export function getSymbolCacheEntry(symbol: string, contextId: string = 'main-context'): SymbolCacheEntry | undefined {
    const cacheKey = `${contextId}:${symbol}`;
    let entry: SymbolCacheEntry | undefined;

    const unsubscribe = symbolCacheStore.subscribe(cache => {
        entry = cache.get(cacheKey);
    });
    unsubscribe(); // Immediately unsubscribe

    return entry;
}

/**
 * Clear cache entries for a specific context
 */
export function clearContextCache(contextId: string): void {
    symbolCacheStore.update(cache => {
        const newCache = new Map(cache);
        const prefix = `${contextId}:`;
        let clearedCount = 0;

        for (const key of Array.from(newCache.keys())) {
            if (key.startsWith(prefix)) {
                newCache.delete(key);
                clearedCount++;
            }
        }

        log.debug(`Cleared ${clearedCount} entries for context: '${contextId}'. Remaining cache size: ${newCache.size}`);

        // Reset stats only if cache is completely empty AND we cleared entries
        if (newCache.size === 0 && clearedCount > 0) {
            cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0, responses: 0, lastUpdate: 0, symbolsFound: 0, symbolsNotFound: 0};
            log.debug('Cache stats reset after context clear');
        }

        return newCache;
    });
}

/**
 * Clear entire symbol cache
 */
export function clearSymbolCache(): void {
    symbolCacheStore.update(cache => {
        const previousSize = cache.size;

        // Reset statistics
        cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0, responses: 0, lastUpdate: 0, symbolsFound: 0, symbolsNotFound: 0};

        log.debug(`Symbol cache completely cleared. Removed ${previousSize} entries. Stats reset.`);

        return new Map();
    });
}

/**
 * Get cache statistics for debugging
 */
export function getCacheStats() {
    return {...cacheStats};
}

/**
 * Get current cache size
 */
export function getCacheSize(): number {
    let size = 0;
    const unsubscribe = symbolCacheStore.subscribe(cache => {
        size = cache.size;
    });
    unsubscribe();
    return size;
}

/**
 * Get current number of in-flight requests
 */
export function getInflightRequestsCount(): number {
    return inflightRequests.size;
}

/**
 * Debug function to inspect cache contents
 */
export function debugCache(): void {
    symbolCacheStore.subscribe(cache => {
        console.log('CACHE DEBUG - Total entries:', cache.size);
        for (const [key, entry] of cache.entries()) {
            console.log(`  ${key}: status="${entry.status}" fqn="${entry.fqn}" contextId="${entry.contextId}"`);
        }
    })();
}

/**
 * Prepare for context switch by setting minimum valid sequence
 * This will cause responses from before the context switch to be discarded
 */
export function prepareContextSwitch(newContextId: string): void {
    const newSeq = getNextSequence(newContextId);
    // Set the minimum valid sequence for this context to the new sequence
    // This ensures any responses from before this context switch are rejected
    contextSwitchSequences.set(newContextId, newSeq);
    log.debug(`Prepared context switch to ${newContextId} with sequence ${newSeq} as minimum valid`);

    // Reset last request time for new context to ensure next request is immediate
    lastRequestTime.delete(newContextId);

    // Clear any pending requests for the new context
    const keysToRemove: string[] = [];
    for (const key of inflightRequests.keys()) {
        if (key.startsWith(`${newContextId}:`)) {
            keysToRemove.push(key);
        }
    }

    keysToRemove.forEach(key => {
        inflightRequests.delete(key);
        log.debug(`Cleared in-flight request: ${key}`);
    });
}

// Make debug function available globally for browser console
if (typeof window !== 'undefined') {
    (window as any).debugSymbolCache = debugCache;
}