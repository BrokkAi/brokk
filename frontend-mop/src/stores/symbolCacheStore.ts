import {writable} from 'svelte/store';
import {createWorkerLogger} from '../lib/logging';

const log = createWorkerLogger('symbol-cache-store');

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

    console.warn(`🔍 SYMBOL LOOKUP REQUEST: symbol="${symbol}", contextId="${contextId}", cacheKey="${cacheKey}"`);

    symbolCacheStore.update(cache => {
        const newCache = new Map(cache);

        // Check if already cached or pending
        const existing = newCache.get(cacheKey);
        console.warn(`📋 CACHE CHECK: existing=${existing ? `{status:"${existing.status}", fqn:"${existing.fqn}"}` : 'null'}`);

        if (existing) {
            if (existing.status === 'resolved' && existing.fqn) {
                // Only cache hit for symbols that actually exist (have fqn)
                cacheStats.hits++;
                console.warn(`✅ CACHE HIT: symbol="${symbol}" already resolved with fqn="${existing.fqn}"`);
                log.debug(`Cache HIT for '${symbol}' -> fqn:${existing.fqn}`);
                return cache; // No change needed
            } else if (existing.status === 'resolved' && !existing.fqn) {
                // Symbol was previously not found - allow re-lookup in case codebase changed
                console.warn(`🔄 CACHE MISS (NOT FOUND): symbol="${symbol}" was not found before, re-trying lookup`);
                log.debug(`Cache MISS for '${symbol}' - symbol was not found, allowing re-lookup`);
                // Fall through to request new lookup
            } else if (existing.status === 'pending') {
                console.warn(`⏳ ALREADY PENDING: symbol="${symbol}" lookup already in progress`);
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
        console.warn(`🚀 MARKED AS PENDING: symbol="${symbol}" cacheKey="${cacheKey}" - NO fqn property yet`);

        console.warn(`🔄 CACHE MISS: symbol="${symbol}" marked as pending, requesting lookup`);
        log.debug(`Cache MISS for '${symbol}' (key: '${cacheKey}') - requesting lookup`);

        // Request symbol lookup from Java bridge
        const bridgeAvailable = !!window.javaBridge;
        const methodAvailable = !!window.javaBridge?.onSymbolLookupRequest;
        const bridgeType = typeof window.javaBridge;
        const bridgeMethods = window.javaBridge ? Object.getOwnPropertyNames(window.javaBridge).filter(name => typeof window.javaBridge[name] === 'function') : [];

        console.warn(`🌉 BRIDGE CHECK: javaBridge available = ${bridgeAvailable}, onSymbolLookupRequest = ${methodAvailable}`);
        console.warn(`🌉 BRIDGE TYPE CHECK: javaBridge type = ${bridgeType}, methods = [${bridgeMethods.join(', ')}]`);

        if (window.javaBridge?.onSymbolLookupRequest) {
            console.warn(`📞 CALLING JAVA BRIDGE: symbol="${symbol}", contextId="${contextId}"`);
            window.javaBridge.onSymbolLookupRequest(symbol, contextId);
        } else {
            console.error(`❌ JAVA BRIDGE NOT AVAILABLE: bridgeAvailable=${bridgeAvailable}, methodAvailable=${methodAvailable}`);
            console.error('JavaBridge not available for symbol lookup');
        }

        return newCache;
    });
}

/**
 * Handle symbol resolution response from Java bridge
 */
export function onSymbolResolutionResponse(results: Record<string, string>, contextId: string = 'main-context'): void {
    console.warn(`📥 JAVA BRIDGE RESPONSE: ${Object.keys(results).length} symbols for contextId="${contextId}"`);
    console.warn(`📄 RESPONSE DETAILS: ${JSON.stringify(results)}`);
    console.warn(`Symbol resolution response: ${Object.keys(results).length} symbols for context '${contextId}'`);

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
        console.log(`💾 UPDATING CACHE: Processing ${Object.keys(results).length} resolved entries`);
        for (const [symbol, fqn] of Object.entries(results)) {
            const cacheKey = `${contextId}:${symbol}`;
            console.log(`💾 CACHING RESOLVED SYMBOL: symbol="${symbol}" fqn="${fqn}" cacheKey="${cacheKey}"`);
            newCache.set(cacheKey, {
                fqn,
                status: 'resolved',
                contextId
            });
            updatedCount++;
            log.debug(`Cached '${symbol}' (key:'${cacheKey}') -> fqn:${fqn}`);
        }

        // Handle symbols that were requested but not resolved (don't exist)
        // Find all pending symbols for this context that weren't in the results
        let notFoundCount = 0;
        for (const [key, entry] of newCache.entries()) {
            if (entry.status === 'pending' && key.startsWith(contextId + ':')) {
                const symbolName = key.substring((contextId + ':').length);
                if (!results.hasOwnProperty(symbolName)) {
                    // Symbol was requested but not found in results - mark as resolved with no fqn
                    console.log(`❌ SYMBOL NOT FOUND: symbol="${symbolName}" cacheKey="${key}" - marking as resolved without fqn`);
                    newCache.set(key, {
                        status: 'resolved',
                        contextId
                        // Deliberately no fqn property - this indicates symbol doesn't exist
                    });
                    notFoundCount++;
                    log.debug(`Symbol '${symbolName}' not found - cached as resolved without fqn`);
                }
            }
        }

        if (notFoundCount > 0) {
            console.warn(`🚫 MARKED ${notFoundCount} SYMBOLS AS NOT FOUND`);
        }

        // Check for any pending symbols that weren't resolved
        let pendingCount = 0;
        for (const [key, entry] of newCache.entries()) {
            if (entry.status === 'pending' && key.startsWith(contextId + ':')) {
                pendingCount++;
                console.warn(`⏳ STILL PENDING: key="${key}" entry=${JSON.stringify(entry)}`);
            }
        }

        console.warn(`✨ CACHE UPDATE COMPLETE: Updated ${updatedCount} symbols. ${pendingCount} still pending. Total size: ${newCache.size}/${SYMBOL_CACHE_LIMIT}`);
        console.warn(`Updated ${updatedCount} symbols in cache. Total size: ${newCache.size}/${SYMBOL_CACHE_LIMIT}`);

        // Log cache statistics periodically
        if (cacheStats.requests % 10 === 0) {
            const hitRate = ((cacheStats.hits / (cacheStats.hits + cacheStats.misses)) * 100).toFixed(1);
            console.warn(`Cache Stats: requests:${cacheStats.requests} hits:${cacheStats.hits} misses:${cacheStats.misses} hit-rate:${hitRate}% size:${newCache.size}/${SYMBOL_CACHE_LIMIT} evictions:${cacheStats.evictions}`);
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

    symbolCacheStore.subscribe(cache => {
        entry = cache.get(cacheKey);
    })();

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

        console.warn(`Cleared ${clearedCount} entries for context: '${contextId}'. Remaining cache size: ${newCache.size}`);

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

        console.warn(`Symbol cache completely cleared. Removed ${previousSize} entries. Stats reset.`);

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
        console.log('🔍 CACHE DEBUG - Total entries:', cache.size);
        for (const [key, entry] of cache.entries()) {
            console.log(`  ${key}: status="${entry.status}" fqn="${entry.fqn}" contextId="${entry.contextId}"`);
        }
    })();
}

// Make debug function available globally for browser console
if (typeof window !== 'undefined') {
    (window as any).debugSymbolCache = debugCache;
}