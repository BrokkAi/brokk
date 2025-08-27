import {writable} from 'svelte/store';
import {createLogger} from '../lib/logging';

const log = createLogger('symbol-cache-store');

// Symbol cache entry with resolution status
export interface SymbolCacheEntry {
    fqn?: string;
    status: 'pending' | 'resolved';
    contextId: string;
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
    totalSymbolsProcessed: 0
};

/**
 * Request symbol resolution for a symbol within a context
 */
export function requestSymbolResolution(symbol: string, contextId: string = 'main-context'): void {
    const cacheKey = `${contextId}:${symbol}`;

    log.debug(`Symbol lookup request: ${symbol} (context: ${contextId})`);

    symbolCacheStore.update(cache => {
        const newCache = new Map(cache);

        // Check if already cached or pending
        const existing = newCache.get(cacheKey);
        log.debug(`Cache check for ${symbol}: ${existing ? 'found' : 'not found'}`);

        if (existing) {
            if (existing.status === 'resolved' && existing.fqn) {
                // Only cache hit for symbols that actually exist (have fqn)
                cacheStats.hits++;
                log.debug(`Cache hit for ${symbol}: ${existing.fqn}`);
                return cache; // No change needed
            } else if (existing.status === 'resolved' && !existing.fqn) {
                // Symbol was previously not found - allow re-lookup in case codebase changed
                log.debug(`Cache miss for '${symbol}' - symbol was not found, allowing re-lookup`);
                // Fall through to request new lookup
            } else if (existing.status === 'pending') {
                log.debug(`Symbol '${symbol}' already pending resolution`);
                return cache; // No change needed
            }
        }

        // Mark as pending and request lookup from Java bridge
        cacheStats.requests++;
        cacheStats.misses++;
        cacheStats.totalSymbolsProcessed++;

        newCache.set(cacheKey, {
            status: 'pending',
            contextId
        });
        log.debug(`Cache miss for '${symbol}' (key: '${cacheKey}') - requesting lookup`);

        // Request symbol lookup from Java bridge
        if (window.javaBridge?.lookupSymbolsAsync) {
            log.debug(`Calling Java bridge for symbol: ${symbol}`);
            // Convert single symbol to array and use null sequence for reactive frontend
            window.javaBridge.lookupSymbolsAsync(JSON.stringify([symbol]), null, contextId);
        } else {
            log.error('JavaBridge not available for symbol lookup');
        }

        return newCache;
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
    const actualContextId = typeof seqOrContextId === 'string' ? seqOrContextId : (contextId || 'main-context');
    const seq = typeof seqOrContextId === 'number' ? seqOrContextId : null;
    log.debug(`Symbol resolution response: ${Object.keys(results).length} symbols for context '${actualContextId}' ${seq !== null ? `seq=${seq}` : ''}`);

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
            newCache.set(cacheKey, {
                fqn,
                status: 'resolved',
                contextId: actualContextId
            });
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
                    newCache.set(key, {
                        status: 'resolved',
                        contextId: actualContextId
                        // Deliberately no fqn property - this indicates symbol doesn't exist
                    });
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

        // Reset stats if cache is empty
        if (newCache.size === 0) {
            cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0};
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
        cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalSymbolsProcessed: 0};

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
    symbolCacheStore.subscribe(cache => {
        size = cache.size;
    })();
    return size;
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

// Make debug function available globally for browser console
if (typeof window !== 'undefined') {
    (window as any).debugSymbolCache = debugCache;
}