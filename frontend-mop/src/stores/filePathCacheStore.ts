import {writable, get, readable} from 'svelte/store';
import {createLogger} from '../lib/logging';

// Backend response interfaces (matches Java records)
export interface ProjectFileMatch {
    relativePath: string;      // project-relative path
    absolutePath: string;      // full system path
    isDirectory: boolean;
    lineNumber?: number;       // parsed from input like "file.js:42"
    lineRange?: [number, number]; // for "file.py:15-20"
}

export interface FilePathLookupResult {
    exists: boolean;
    matches: ProjectFileMatch[];
    confidence: number;
    processingTimeMs: number;
}

const log = createLogger('file-path-cache-store');

// Cache configuration
export const FILE_PATH_CACHE_CONFIG = {
    IMMEDIATE_THRESHOLD: 100, // ms - if no requests for 100ms, next one is immediate
    BATCH_DELAY: 15, // ms - reduced delay for subsequent requests
    FILE_PATH_CACHE_LIMIT: 1000, // maximum number of cached file paths
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

// Track pending file paths per context (cumulative until resolved)
const pendingByContext = new Map<string, Set<string>>();

// File path cache entry with resolution status
export interface FilePathCacheEntry {
    result?: FilePathLookupResult | null;
    status: 'pending' | 'resolved';
    contextId: string;
    timestamp: number;
    accessCount: number;
}

// Create reactive store for file path cache
export const filePathCacheStore = writable<Map<string, FilePathCacheEntry>>(new Map());

// Key-scoped subscription infrastructure for performance optimization
const keyListeners = new Map<string, Set<(v: FilePathCacheEntry | undefined) => void>>();

function notifyKey(key: string): void {
    const listeners = keyListeners.get(key);
    if (!listeners) return;
    const val = get(filePathCacheStore).get(key);
    for (const fn of listeners) fn(val);
}

/**
 * Create a key-scoped subscription that only updates when the specific cache key changes
 * This prevents the N×M scaling problem where all components wake up on every cache update
 */
export function subscribeKey(cacheKey: string) {
    return readable<FilePathCacheEntry | undefined>(get(filePathCacheStore).get(cacheKey), (set) => {
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
function shallowEqual(a: FilePathCacheEntry | undefined, b: FilePathCacheEntry | undefined): boolean {
    if (a === b) return true;
    if (!a || !b) return false;

    // Compare basic fields
    if (a.status !== b.status || a.contextId !== b.contextId) {
        return false;
    }

    // Compare result existence status and match count
    const aExists = a.result?.exists;
    const bExists = b.result?.exists;
    const aMatchCount = a.result?.matches?.length || 0;
    const bMatchCount = b.result?.matches?.length || 0;

    return aExists === bExists && aMatchCount === bMatchCount;
}

/**
 * Mark a file path as pending for a specific context
 */
function markPending(contextId: string, filePath: string): void {
    let s = pendingByContext.get(contextId);
    if (!s) pendingByContext.set(contextId, (s = new Set()));
    s.add(filePath);
}

/**
 * Settle pending file paths for a context based on batch results
 * Returns the file paths that were pending but not resolved (not found)
 */
function settlePending(contextId: string, resolved: Set<string>): Set<string> {
    const s = pendingByContext.get(contextId);
    if (!s) return new Set();

    const notFound = new Set<string>();
    for (const filePath of s) {
        if (resolved.has(filePath)) {
            s.delete(filePath);
        } else {
            // This file path was pending but not in results - it wasn't found
            notFound.add(filePath);
            s.delete(filePath);
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
function upsertKey(newCache: Map<string, FilePathCacheEntry>, key: string, next: FilePathCacheEntry): boolean {
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
    totalFilePathsProcessed: 0,
    responses: 0,
    lastUpdate: 0,
    filePathsFound: 0,
    filePathsNotFound: 0
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

function shouldProcessImmediately(prevLastRequest: number, now: number): boolean {
    const timeSinceLastRequest = now - prevLastRequest;
    const isFirstRequest = prevLastRequest === 0;
    return isFirstRequest || timeSinceLastRequest >= FILE_PATH_CACHE_CONFIG.IMMEDIATE_THRESHOLD;
}

function addToBatch(contextId: string, filePath: string): void {
    const now = Date.now();

    // Check if this is the first request for this context BEFORE updating lastRequestTime
    const isFirstRequest = !lastRequestTime.has(contextId);
    const prevLastRequest = lastRequestTime.get(contextId) || 0;

    if (!pendingBatchRequests.has(contextId)) {
        pendingBatchRequests.set(contextId, new Set());
    }

    pendingBatchRequests.get(contextId)!.add(filePath);

    // Determine if we should process immediately or batch (per-context timer)
    const contextTimer = batchTimers.get(contextId);
    const shouldProcessNow = shouldProcessImmediately(prevLastRequest, now) && contextTimer === undefined;

    if (shouldProcessNow) {
        // Process immediately for first request or after inactivity
        processBatchForContext(contextId, true); // true = immediate processing
    } else {
        // Schedule batch processing with delay for rapid follow-up requests
        if (contextTimer === undefined) {
            // Use 0ms delay for the very first batch, normal delay for subsequent batches
            const delay = isFirstRequest ? 0 : FILE_PATH_CACHE_CONFIG.BATCH_DELAY;
            const timerId = window.setTimeout(() => {
                processBatchForContext(contextId, false); // false = batched processing
                batchTimers.delete(contextId);
            }, delay);
            batchTimers.set(contextId, timerId);
        }
    }

    // Update last request time after decision
    lastRequestTime.set(contextId, now);
}

/**
 * Process batch for a specific context
 */
function processBatchForContext(contextId: string, immediate: boolean = false): void {
    const filePaths = pendingBatchRequests.get(contextId);
    log.debug(`Processing file path batch for context="${contextId}" (immediate=${immediate}) with ${filePaths ? filePaths.size : 0} item(s)`);
    if (!filePaths || filePaths.size === 0) {
        return;
    }

    const filePathArray = Array.from(filePaths);

    // Track these file paths as pending for this context
    for (const filePath of filePathArray) {
        markPending(contextId, filePath);
    }

    // Make single batched request
    const sequence = getNextSequence(contextId);

    if (window.javaBridge?.lookupFilePathsAsync) {
        window.javaBridge.lookupFilePathsAsync(
            JSON.stringify(filePathArray),
            sequence,
            contextId
        );
    }

    // Mark all file paths as pending in cache
    filePathCacheStore.update(cache => {
        const newCache = new Map(cache);
        for (const filePath of filePathArray) {
            const cacheKey = `${contextId}:${filePath}`;
            if (!newCache.has(cacheKey) || newCache.get(cacheKey)?.status !== 'resolved') {
                const pendingEntry: FilePathCacheEntry = {
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
 * Request file path resolution for a file path within a context
 */
export function requestFilePathResolution(filePath: string, contextId: string = 'main-context'): Promise<void> {
    const cacheKey = `${contextId}:${filePath}`;

    // Check if request already in flight
    if (inflightRequests.has(cacheKey)) {
        return inflightRequests.get(cacheKey)!;
    }

    // Atomic check-and-set operation
    const requestPromise = performAtomicFilePathLookup(filePath, contextId, cacheKey);

    // Track in-flight request
    inflightRequests.set(cacheKey, requestPromise);

    // Clean up when done
    requestPromise.finally(() => {
        inflightRequests.delete(cacheKey);
    });

    return requestPromise;
}

/**
 * Atomic file path lookup with proper race condition handling
 */
async function performAtomicFilePathLookup(filePath: string, contextId: string, cacheKey: string): Promise<void> {
    return new Promise<void>((resolve) => {
        filePathCacheStore.update(cache => {
            // Check if already resolved while waiting
            const existing = cache.get(cacheKey);
            if (existing?.status === 'resolved') {
                cacheStats.hits++;
                resolve();
                return cache; // No changes needed
            }

            // Set to pending state
            const newCache = new Map(cache);
            const pendingEntry: FilePathCacheEntry = {
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
            cacheStats.totalFilePathsProcessed++;

            // Always use batching system for backend calls
            if (window.javaBridge?.lookupFilePathsAsync) {
                addToBatch(contextId, filePath);
            } else {
                // No bridge at all - resolve with not found
                setTimeout(() => {
                    filePathCacheStore.update(cache => {
                        const updatedCache = new Map(cache);
                        const resolvedEntry: FilePathCacheEntry = {
                            result: {
                                exists: false,
                                matches: [],
                                confidence: 0,
                                processingTimeMs: 0
                            },
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
        log.warn('onFilePathResolutionResponse received legacy signature without sequence; consider updating producer to include sequence for proper validation');
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
function evictCacheEntriesIfNeeded(cache: Map<string, FilePathCacheEntry>, newEntriesCount: number): void {
    const currentSize = cache.size;
    if (currentSize + newEntriesCount <= FILE_PATH_CACHE_CONFIG.FILE_PATH_CACHE_LIMIT) {
        return;
    }

    const toEvict = (currentSize + newEntriesCount) - FILE_PATH_CACHE_CONFIG.FILE_PATH_CACHE_LIMIT;

    // LRU eviction: evict entries with the oldest timestamps first
    // Build an array of [key, entry], sort by timestamp ascending
    const entries = Array.from(cache.entries());
    entries.sort((a, b) => {
        const at = a[1]?.timestamp ?? 0;
        const bt = b[1]?.timestamp ?? 0;
        return at - bt;
    });

    const keysToDelete: string[] = [];
    for (let i = 0; i < entries.length && keysToDelete.length < toEvict; i++) {
        keysToDelete.push(entries[i][0]);
    }

    keysToDelete.forEach(key => {
        cache.delete(key);
        cacheStats.evictions++;
    });
}

/**
 * Update cache with resolved file paths
 */
function updateResolvedFilePaths(cache: Map<string, FilePathCacheEntry>,
                                results: Record<string, FilePathLookupResult>,
                                contextId: string): { keysToNotify: string[]; updatedCount: number } {
    const keysToNotify: string[] = [];
    let updatedCount = 0;

    for (const [filePath, filePathResult] of Object.entries(results)) {
        const cacheKey = `${contextId}:${filePath}`;

        // Normalize by ignoring directory matches; only file matches count
        const filteredMatches = (filePathResult.matches || []).filter(m => !m.isDirectory);
        const normalizedResult: FilePathLookupResult = {
            ...filePathResult,
            exists: filteredMatches.length > 0,
            matches: filteredMatches
        };

        const resolvedEntry: FilePathCacheEntry = {
            result: normalizedResult,
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
            if (normalizedResult.processingTimeMs) {
                const found = normalizedResult.exists ? 'found' : 'not found';
                const matchCount = filteredMatches.length;
                log.debug(`[PERF][FRONTEND] FilePath '${filePath}' ${found} (${matchCount} matches, ${normalizedResult.confidence}% confidence, ${normalizedResult.processingTimeMs}ms)`);
            }
        }
    }

    return { keysToNotify, updatedCount };
}

/**
 * Handle file paths that were requested but not found in results
 */
function updateNotFoundFilePaths(cache: Map<string, FilePathCacheEntry>,
                                resolvedFilePaths: Set<string>,
                                contextId: string): { keysToNotify: string[]; notFoundCount: number } {
    const notFoundFilePaths = settlePending(contextId, resolvedFilePaths);
    const keysToNotify: string[] = [];
    let notFoundCount = 0;

    for (const filePathName of notFoundFilePaths) {
        const cacheKey = `${contextId}:${filePathName}`;

        const notFoundEntry: FilePathCacheEntry = {
            result: {
                exists: false,
                matches: [],
                confidence: 0,
                processingTimeMs: 0
            },
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
 * Handle file path resolution response from Java bridge
 * @param results - Map of file path names to their FilePathLookupResults
 * @param seqOrContextId - Either sequence number (legacy) or contextId (reactive)
 * @param contextId - Context ID (when sequence is provided)
 */
export function onFilePathResolutionResponse(results: Record<string, FilePathLookupResult>, seqOrContextId: number | string = 'main-context', contextId?: string): void {
    try {
        const { contextId: actualContextId } = parseResponseParams(seqOrContextId, contextId);

        if (Object.keys(results).length === 0) {
            return;
        }

        filePathCacheStore.update(cache => {
            const newCache = new Map(cache);

            // Handle cache size limit with eviction (account for both resolved and "not found" pending)
        const resolvedCount = Object.keys(results).length;
        const resolvedFilePathsSet = new Set(Object.keys(results));
        const pendingSet = pendingByContext.get(actualContextId);
        let notFoundCountEstimate = 0;
        if (pendingSet && pendingSet.size > 0) {
            for (const pending of pendingSet) {
                if (!resolvedFilePathsSet.has(pending)) {
                    notFoundCountEstimate++;
                }
            }
        }
        const totalNewEntries = resolvedCount + notFoundCountEstimate;
        evictCacheEntriesIfNeeded(newCache, totalNewEntries);

            // Update cache with resolved file paths
            const resolvedUpdate = updateResolvedFilePaths(newCache, results, actualContextId);

            // Handle file paths that were requested but not resolved
            const resolvedFilePaths = new Set(Object.keys(results));
            const notFoundUpdate = updateNotFoundFilePaths(newCache, resolvedFilePaths, actualContextId);

            // Update cache stats
            cacheStats.responses++;
            cacheStats.lastUpdate = Date.now();
            cacheStats.filePathsFound += resolvedUpdate.updatedCount;
            cacheStats.filePathsNotFound += notFoundUpdate.notFoundCount;

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
 * Get file path cache entry for a given file path and context
 * Updates access count and timestamp for LRU tracking
 */
export function getFilePathCacheEntry(filePath: string, contextId: string = 'main-context'): FilePathCacheEntry | undefined {
    const cacheKey = `${contextId}:${filePath}`;
    const entry = get(filePathCacheStore).get(cacheKey);

    if (entry) {
        // Update access count and timestamp for LRU tracking
        const updatedEntry: FilePathCacheEntry = {
            ...entry,
            accessCount: entry.accessCount + 1,
            timestamp: Date.now()
        };

        // Update the cache with incremented access count
        // Don't use upsertKey here since we're intentionally updating metadata
        filePathCacheStore.update(cache => {
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
    filePathCacheStore.update(cache => {
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
            cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalFilePathsProcessed: 0, responses: 0, lastUpdate: 0, filePathsFound: 0, filePathsNotFound: 0};
        }

        return newCache;
    });
}

/**
 * Clear entire file path cache
 */
export function clearFilePathCache(contextId = 'main-context'): void {
    filePathCacheStore.update(cache => {
        const previousSize = cache.size;

        // Reset statistics
        cacheStats = {requests: 0, hits: 0, misses: 0, evictions: 0, totalFilePathsProcessed: 0, responses: 0, lastUpdate: 0, filePathsFound: 0, filePathsNotFound: 0};

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
    return get(filePathCacheStore).size;
}

/**
 * Get current number of in-flight requests
 */
export function getInflightRequestsCount(): number {
    return inflightRequests.size;
}

/**
 * Get cache contents for debugging
 */
export function getCacheContents(): Record<string, any> {
    const cache = get(filePathCacheStore);
    const contents: Record<string, any> = {};

    for (const [key, entry] of cache.entries()) {
        contents[key] = {
            status: entry.status,
            contextId: entry.contextId,
            timestamp: entry.timestamp,
            accessCount: entry.accessCount,
            result: entry.result ? {
                exists: entry.result.exists,
                matchCount: entry.result.matches.length,
                confidence: entry.result.confidence,
                processingTimeMs: entry.result.processingTimeMs,
                matches: entry.result.matches.map(match => ({
                    relativePath: match.relativePath,
                    absolutePath: match.absolutePath,
                    isDirectory: match.isDirectory,
                    lineNumber: match.lineNumber,
                    lineRange: match.lineRange
                }))
            } : null
        };
    }

    return contents;
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
export function debugFilePathCache(): void {
    filePathCacheStore.subscribe(cache => {
        log.debug('FILE PATH CACHE DEBUG - Total entries:', cache.size);
        for (const [key, entry] of cache.entries()) {
            log.debug(`  ${key}: status="${entry.status}" exists="${entry.result?.exists}" matches="${entry.result?.matches?.length || 0}" contextId="${entry.contextId}"`);
        }
    })();
}
