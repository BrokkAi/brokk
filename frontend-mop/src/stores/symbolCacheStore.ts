import {writable, get, readable} from 'svelte/store';
import {createLogger} from '../lib/logging';
import {mockExtractSymbol, type MockSymbolResult} from '../dev/mockSymbolExtractor';

// Backend response interfaces (matches Java records)
export interface HighlightRange {
    start: number;
    end: number;
}

export interface SymbolLookupResult {
    fqn: string | null;
    highlightRanges: HighlightRange[];
    isPartialMatch: boolean;
    originalText: string | null;
    confidence?: number;              // 0-100 confidence score (optional for backward compatibility)
    processingTimeMs?: number;        // Processing time in milliseconds (optional for backward compatibility)
}

const log = createLogger('symbol-cache-store');

// Cache configuration
export const CACHE_CONFIG = {
    IMMEDIATE_THRESHOLD: 100, // ms - if no requests for 100ms, next one is immediate
    BATCH_DELAY: 15, // ms - reduced delay for subsequent requests
    SYMBOL_CACHE_LIMIT: 1000, // maximum number of cached symbols
} as const;

// Add request deduplication infrastructure
const inflightRequests = new Map<string, Promise<void>>();

// Add sequence tracking for validation
let currentSequence = 0;
const contextSequences = new Map<string, number>();
// Track context switch sequences separately for proper validation
const contextSwitchSequences = new Map<string, number>();

// Adaptive batch coordination for component requests
const batchTimers = new Map<string, number>(); // Per-context batch timers
const pendingBatchRequests = new Map<string, Set<string>>();
// Track last request time per context to determine if we should batch
const lastRequestTime = new Map<string, number>();

// Track pending symbol names per context (cumulative until resolved)
const pendingByContext = new Map<string, Set<string>>();

// Symbol cache entry with resolution status
export interface SymbolCacheEntry {
    result?: MockSymbolResult | SymbolLookupResult | null;
    status: 'pending' | 'resolved';
    contextId: string;
    timestamp: number;
    accessCount: number;
}

// Create reactive store for symbol cache
export const symbolCacheStore = writable<Map<string, SymbolCacheEntry>>(new Map());

// Key-scoped subscription infrastructure for performance optimization
const keyListeners = new Map<string, Set<(v: SymbolCacheEntry | undefined) => void>>();

function notifyKey(key: string): void {
    const listeners = keyListeners.get(key);
    if (!listeners) return;
    const val = get(symbolCacheStore).get(key);
    for (const fn of listeners) fn(val);
}

/**
 * Create a key-scoped subscription that only updates when the specific cache key changes
 * This prevents the N×M scaling problem where all components wake up on every cache update
 */
export function subscribeKey(cacheKey: string) {
    return readable<SymbolCacheEntry | undefined>(get(symbolCacheStore).get(cacheKey), (set) => {
        let s = keyListeners.get(cacheKey);
        if (!s) keyListeners.set(cacheKey, (s = new Set()));
        s.add(set);
        // Initial value is handled by readable()'s start argument above
        return () => {
            s!.delete(set);
            if (!s!.size) keyListeners.delete(cacheKey);
        };
    });
}

/**
 * Compare two cache entries for shallow equality based on meaningful fields
 * Ignores timestamp and accessCount to prevent spurious updates
 */
function shallowEqual(a: SymbolCacheEntry | undefined, b: SymbolCacheEntry | undefined): boolean {
    if (a === b) return true;
    if (!a || !b) return false;

    // Compare basic fields
    if (a.status !== b.status || a.contextId !== b.contextId) {
        return false;
    }

    // Compare result FQNs
    const aFqn = a.result?.fqn;
    const bFqn = b.result?.fqn;

    return aFqn === bFqn;
}

/**
 * Mark a symbol as pending for a specific context
 */
function markPending(contextId: string, symbol: string): void {
    let s = pendingByContext.get(contextId);
    if (!s) pendingByContext.set(contextId, (s = new Set()));
    s.add(symbol);
}

/**
 * Settle pending symbols for a context based on batch results
 * Returns the symbols that were pending but not resolved (not found)
 */
function settlePending(contextId: string, resolved: Set<string>): Set<string> {
    const s = pendingByContext.get(contextId);
    if (!s) return new Set();

    const notFound = new Set<string>();
    for (const sym of s) {
        if (resolved.has(sym)) {
            s.delete(sym);
        } else {
            // This symbol was pending but not in results - it wasn't found
            notFound.add(sym);
            s.delete(sym);
        }
    }

    // Clean up empty sets
    if (!s.size) pendingByContext.delete(contextId);
    return notFound;
}

/**
 * Upsert a cache entry only if it has actually changed
 * Returns true if the entry was updated, false if no change was needed
 */
function upsertKey(newCache: Map<string, SymbolCacheEntry>, key: string, next: SymbolCacheEntry): boolean {
    const prev = newCache.get(key);
    if (shallowEqual(prev, next)) {
        return false; // Nothing to do - content is the same
    }
    newCache.set(key, next);
    return true; // Entry was updated
}

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
        log.warn(`Ignoring stale sequence ${seq} for context ${contextId}, min valid: ${minValidSeq}`);
    }

    return isValid;
}

function shouldProcessImmediately(contextId: string): boolean {
    const now = Date.now();
    const lastRequest = lastRequestTime.get(contextId) || 0;
    const timeSinceLastRequest = now - lastRequest;

    // Process immediately if:
    // 1. This is the first request for this context (lastRequest === 0), OR
    // 2. Enough time has passed since the last request (indicates user is not rapidly typing)
    const isFirstRequest = lastRequest === 0;
    const immediate = isFirstRequest || timeSinceLastRequest >= CACHE_CONFIG.IMMEDIATE_THRESHOLD;

    return immediate;
}

function addToBatch(contextId: string, symbol: string): void {
    const now = Date.now();

    // Check if this is the first request for this context BEFORE updating lastRequestTime
    const isFirstRequest = !lastRequestTime.has(contextId);

    // Update last request time for this context
    lastRequestTime.set(contextId, now);

    if (!pendingBatchRequests.has(contextId)) {
        pendingBatchRequests.set(contextId, new Set());
    }

    pendingBatchRequests.get(contextId)!.add(symbol);

    // Determine if we should process immediately or batch (per-context timer)
    const contextTimer = batchTimers.get(contextId);
    const shouldProcessNow = shouldProcessImmediately(contextId) && contextTimer === undefined;

    if (shouldProcessNow) {
        // Process immediately for first request or after inactivity
        processBatchForContext(contextId, true); // true = immediate processing
    } else {
        // Schedule batch processing with delay for rapid follow-up requests
        if (contextTimer === undefined) {
            // Use 0ms delay for the very first batch, normal delay for subsequent batches
            const delay = isFirstRequest ? 0 : CACHE_CONFIG.BATCH_DELAY;
            const timerId = window.setTimeout(() => {
                processBatchForContext(contextId, false); // false = batched processing
                batchTimers.delete(contextId);
            }, delay);
            batchTimers.set(contextId, timerId);
        }
    }
}

/**
 * Process batch for a specific context
 */
function processBatchForContext(contextId: string, immediate: boolean = false): void {
    const symbols = pendingBatchRequests.get(contextId);
    if (!symbols || symbols.size === 0) {
        return;
    }

    const symbolArray = Array.from(symbols);

    // Track these symbols as pending for this context
    for (const symbol of symbolArray) {
        markPending(contextId, symbol);
    }

    // Make single batched request
    const sequence = getNextSequence(contextId);

    if (window.javaBridge?.lookupSymbolsAsync) {
        window.javaBridge.lookupSymbolsAsync(
            JSON.stringify(symbolArray),
            sequence,
            contextId
        );
    }

    // Mark all symbols as pending in cache
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

    // Clear this batch
    pendingBatchRequests.delete(contextId);
}

/**
 * Request symbol resolution for a symbol within a context
 */
export function requestSymbolResolution(symbol: string, contextId: string = 'main-context'): Promise<void> {
    const cacheKey = `${contextId}:${symbol}`;

    // Check if request already in flight
    if (inflightRequests.has(cacheKey)) {
        return inflightRequests.get(cacheKey)!;
    }

    // Atomic check-and-set operation
    const requestPromise = performAtomicSymbolLookup(symbol, contextId, cacheKey);

    // Track in-flight request
    inflightRequests.set(cacheKey, requestPromise);

    // Clean up when done
    requestPromise.finally(() => {
        inflightRequests.delete(cacheKey);
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
                cacheStats.hits++;
                resolve();
                return cache; // No changes needed
            }

            // Try to extract mock symbol result
            const mockResult = mockExtractSymbol(symbol);

            // Set to pending state
            const newCache = new Map(cache);
            const pendingEntry: SymbolCacheEntry = {
                result: mockResult,
                status: 'pending',
                contextId: contextId,
                timestamp: Date.now(),
                accessCount: 1
            };

            // Only update and notify if the entry actually changed
            const wasUpdated = upsertKey(newCache, cacheKey, pendingEntry);
            if (wasUpdated) {
                setTimeout(() => notifyKey(cacheKey), 0);
            }

            // Update stats
            cacheStats.requests++;
            cacheStats.misses++;
            cacheStats.totalSymbolsProcessed++;


            // Always use batching system for real backend calls
            // Mock results are processed in the cache entry but resolved via backend
            if (window.javaBridge?.lookupSymbolsAsync) {
                addToBatch(contextId, symbol);
            } else {
                // No bridge at all - resolve with mock result if available, otherwise null
                setTimeout(() => {
                    symbolCacheStore.update(cache => {
                        const updatedCache = new Map(cache);
                        const resolvedEntry: SymbolCacheEntry = {
                            result: mockResult,
                            status: 'resolved',
                            contextId: contextId,
                            timestamp: Date.now(),
                            accessCount: 1
                        };
                        const wasUpdated = upsertKey(updatedCache, cacheKey, resolvedEntry);
                        if (wasUpdated) {
                            notifyKey(cacheKey);
                        }
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
 * Parse response parameters to handle both legacy and new signatures
 */
function parseResponseParams(seqOrContextId: number | string, contextId?: string): { contextId: string; sequence: number | null } {
    if (typeof seqOrContextId === 'string') {
        // Legacy signature: (results, contextId)
        return { contextId: seqOrContextId, sequence: null };
    } else {
        // New signature: (results, seq, contextId)
        const sequence = seqOrContextId;
        const actualContextId = contextId || 'main-context';

        // Validate sequence if provided
        if (!isValidSequence(actualContextId, sequence)) {
            throw new Error('Invalid sequence'); // Will be caught by caller
        }

        return { contextId: actualContextId, sequence };
    }
}

/**
 * Evict old cache entries if necessary to stay within limits
 */
function evictCacheEntriesIfNeeded(cache: Map<string, SymbolCacheEntry>, newEntriesCount: number): void {
    const currentSize = cache.size;
    if (currentSize + newEntriesCount <= CACHE_CONFIG.SYMBOL_CACHE_LIMIT) {
        return;
    }

    const toEvict = (currentSize + newEntriesCount) - CACHE_CONFIG.SYMBOL_CACHE_LIMIT;
    const keysToDelete = Array.from(cache.keys()).slice(0, toEvict);

    keysToDelete.forEach(key => {
        cache.delete(key);
        cacheStats.evictions++;
    });
}

/**
 * Update cache with resolved symbols
 */
function updateResolvedSymbols(cache: Map<string, SymbolCacheEntry>,
                              results: Record<string, SymbolLookupResult>,
                              contextId: string): { keysToNotify: string[]; updatedCount: number } {
    const keysToNotify: string[] = [];
    let updatedCount = 0;

    for (const [symbol, symbolResult] of Object.entries(results)) {
        const cacheKey = `${contextId}:${symbol}`;
        const resolvedEntry: SymbolCacheEntry = {
            result: symbolResult,
            status: 'resolved',
            contextId,
            timestamp: Date.now(),
            accessCount: 1
        };

        const wasUpdated = upsertKey(cache, cacheKey, resolvedEntry);
        if (wasUpdated) {
            keysToNotify.push(cacheKey);
            updatedCount++;

            // Log performance metrics for streaming analysis
            if (symbolResult.processingTimeMs) {
                const found = symbolResult.fqn ? 'found' : 'not found';
                const confidence = symbolResult.confidence || 0;
                const matchType = symbolResult.isPartialMatch ? 'partial' : 'exact';
                log.debug(`[PERF][FRONTEND] Symbol '${symbol}' ${found} (${matchType}, ${confidence}% confidence, ${symbolResult.processingTimeMs}ms)`);
            }
        }
    }

    return { keysToNotify, updatedCount };
}

/**
 * Handle symbols that were requested but not found in results
 */
function updateNotFoundSymbols(cache: Map<string, SymbolCacheEntry>,
                              resolvedSymbols: Set<string>,
                              contextId: string): { keysToNotify: string[]; notFoundCount: number } {
    const notFoundSymbols = settlePending(contextId, resolvedSymbols);
    const keysToNotify: string[] = [];
    let notFoundCount = 0;

    for (const symbolName of notFoundSymbols) {
        const cacheKey = `${contextId}:${symbolName}`;
        const existingEntry = cache.get(cacheKey);
        const mockResult = existingEntry?.result;

        const notFoundEntry: SymbolCacheEntry = {
            result: mockResult ? { ...mockResult, fqn: null } : null,
            status: 'resolved',
            contextId,
            timestamp: Date.now(),
            accessCount: 1
        };

        const wasUpdated = upsertKey(cache, cacheKey, notFoundEntry);
        if (wasUpdated) {
            keysToNotify.push(cacheKey);
            notFoundCount++;
        }
    }

    return { keysToNotify, notFoundCount };
}

/**
 * Handle symbol resolution response from Java bridge
 * @param results - Map of symbol names to their SymbolLookupResults (contains FQN, highlight ranges, and partial match info)
 * @param seqOrContextId - Either sequence number (legacy) or contextId (reactive)
 * @param contextId - Context ID (when sequence is provided)
 */
export function onSymbolResolutionResponse(results: Record<string, SymbolLookupResult>, seqOrContextId: number | string = 'main-context', contextId?: string): void {
    try {
        const { contextId: actualContextId } = parseResponseParams(seqOrContextId, contextId);

        if (Object.keys(results).length === 0) {
            return;
        }

        symbolCacheStore.update(cache => {
            const newCache = new Map(cache);

            // Handle cache size limit with eviction
            evictCacheEntriesIfNeeded(newCache, Object.keys(results).length);

            // Update cache with resolved symbols
            const resolvedUpdate = updateResolvedSymbols(newCache, results, actualContextId);

            // Handle symbols that were requested but not resolved
            const resolvedSymbols = new Set(Object.keys(results));
            const notFoundUpdate = updateNotFoundSymbols(newCache, resolvedSymbols, actualContextId);

            // Update cache stats
            cacheStats.responses++;
            cacheStats.lastUpdate = Date.now();
            cacheStats.symbolsFound += resolvedUpdate.updatedCount;
            cacheStats.symbolsNotFound += notFoundUpdate.notFoundCount;

            // Notify subscribers after cache update is complete
            const allKeysToNotify = [...resolvedUpdate.keysToNotify, ...notFoundUpdate.keysToNotify];
            setTimeout(() => {
                for (const key of allKeysToNotify) {
                    notifyKey(key);
                }
            }, 0);

            return newCache;
        });
    } catch (error) {
        // Invalid sequence or other error - silently discard
        return;
    }
}

/**
 * Get symbol cache entry for a given symbol and context
 * Updates access count and timestamp for LRU tracking
 */
export function getSymbolCacheEntry(symbol: string, contextId: string = 'main-context'): SymbolCacheEntry | undefined {
    const cacheKey = `${contextId}:${symbol}`;
    const entry = get(symbolCacheStore).get(cacheKey);

    if (entry) {
        // Update access count and timestamp for LRU tracking
        const updatedEntry: SymbolCacheEntry = {
            ...entry,
            accessCount: entry.accessCount + 1,
            timestamp: Date.now()
        };

        // Update the cache with incremented access count
        // Don't use upsertKey here since we're intentionally updating metadata
        symbolCacheStore.update(cache => {
            const newCache = new Map(cache);
            newCache.set(cacheKey, updatedEntry);
            return newCache;
        });

        // Don't notify subscribers for access count changes - they only care about content changes
        return updatedEntry;
    }

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

        // Reset stats only if cache is completely empty AND we cleared entries
        if (newCache.size === 0 && clearedCount > 0) {
            cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0, responses: 0, lastUpdate: 0, symbolsFound: 0, symbolsNotFound: 0};
        }

        return newCache;
    });
}

/**
 * Clear entire symbol cache
 */
export function clearSymbolCache(contextId = 'main-context'): void {
    symbolCacheStore.update(cache => {
        const previousSize = cache.size;

        // Reset statistics
        cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0, responses: 0, lastUpdate: 0, symbolsFound: 0, symbolsNotFound: 0};

        return new Map();
    });

    // CRITICAL: Also clear in-flight requests to allow new requests after analyzer ready
    const inflightCount = inflightRequests.size;
    inflightRequests.clear();

    // Clear pending batches and reset timers
    pendingBatchRequests.clear();
    pendingByContext.clear();

    // Clear all per-context batch timers
    for (const timerId of batchTimers.values()) {
        clearTimeout(timerId);
    }
    batchTimers.clear();
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
    return get(symbolCacheStore).size;
}

/**
 * Get current number of in-flight requests
 */
export function getInflightRequestsCount(): number {
    return inflightRequests.size;
}

/**
 * Get serializable snapshot of cache contents for debugging
 */
export function getCacheContents(): {
    entries: Array<{
        cacheKey: string;
        symbol: string;
        contextId: string;
        status: string;
        fqn: string | null;
        isPartialMatch: boolean;
        originalText: string | null;
        highlightRanges: Array<{start: number, end: number}>;
        timestamp: number;
        accessCount: number;
    }>;
    stats: typeof cacheStats;
    inflightRequests: string[];
    timestamp: string;
} {
    const cache = get(symbolCacheStore);
    const entries = Array.from(cache.entries()).map(([cacheKey, entry]) => {
        // Extract symbol name from cache key (format: "contextId:symbolName")
        const symbol = cacheKey.split(':').slice(1).join(':');

        return {
            cacheKey,
            symbol,
            contextId: entry.contextId,
            status: entry.status,
            fqn: entry.result?.fqn || null,
            isPartialMatch: entry.result?.isPartialMatch || false,
            originalText: entry.result?.originalText || null,
            highlightRanges: entry.result?.highlightRanges || [],
            timestamp: entry.timestamp,
            accessCount: entry.accessCount
        };
    });

    return {
        entries: entries.sort((a, b) => b.timestamp - a.timestamp), // Sort by most recent first
        stats: { ...cacheStats },
        inflightRequests: Array.from(inflightRequests.keys()),
        timestamp: new Date().toISOString()
    };
}

/**
 * Debug function to inspect cache contents
 */
export function debugCache(): void {
    symbolCacheStore.subscribe(cache => {
        log.debug('CACHE DEBUG - Total entries:', cache.size);
        for (const [key, entry] of cache.entries()) {
            log.debug(`  ${key}: status="${entry.status}" fqn="${entry.result?.fqn}" contextId="${entry.contextId}"`);
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

// Export debug function for proper use instead of global pollution
export { debugCache as debugSymbolCache };
