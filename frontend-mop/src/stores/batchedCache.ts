import { writable, readable, get, type Readable, type Writable } from 'svelte/store';
import { createLogger } from '../lib/logging';

export interface CacheEntry<TResult> {
  result?: TResult | null;
  status: 'pending' | 'resolved';
  contextId: string;
  timestamp: number;
  accessCount: number;
}

export interface BatchedCacheConfig {
  immediateThreshold: number; // ms
  batchDelay: number;         // ms
  limit: number;              // max entries
  loggerName: string;
}

export interface DomainHooks<TInput, TResult> {
  makeCacheKey: (input: TInput, contextId: string) => string;
  pendingInitializer?: (input: TInput) => TResult | null;
  shallowEqualEntries: (a: CacheEntry<TResult> | undefined, b: CacheEntry<TResult> | undefined) => boolean;
  sendBatch: (inputs: TInput[], seq: number, contextId: string) => void;
  normalizeResolved?: (result: TResult) => TResult;
  buildNotFoundEntry: (existingPending?: CacheEntry<TResult>) => CacheEntry<TResult>;
  perfLog?: (input: TInput, result: TResult) => void;
  warnOnLegacyResponse?: boolean;
}

export interface BatchedCacheApi<TInput, TResult> {
  // state
  store: Writable<Map<string, CacheEntry<TResult>>>;
  subscribeKey: (cacheKey: string) => Readable<CacheEntry<TResult> | undefined>;
  // requests
  request: (input: TInput, contextId?: string) => Promise<void>;
  // responses
  onResponse: (results: Record<string, TResult>, seqOrContextId?: number | string, contextId?: string) => void;
  // query
  getEntry: (input: TInput, contextId?: string) => CacheEntry<TResult> | undefined;
  // maintenance
  clearContextCache: (contextId: string) => void;
  clearAll: () => void;
  prepareContextSwitch: (newContextId: string) => void;
  // stats/debug
  getStats: () => Record<string, number>;
  getSize: () => number;
  getInflightCount: () => number;
  getContents: () => Record<string, any>;
  debugDump: () => void;
}

export function createBatchedCache<TInput, TResult>(
  config: BatchedCacheConfig,
  hooks: DomainHooks<TInput, TResult>,
  defaultContextId: string = 'main-context'
): BatchedCacheApi<TInput, TResult> {
  const log = createLogger(config.loggerName);

  const store: Writable<Map<string, CacheEntry<TResult>>> = writable(new Map());

  // key-scoped listeners to avoid waking up all subscribers
  const keyListeners = new Map<string, Set<(v: CacheEntry<TResult> | undefined) => void>>();
  function notifyKey(key: string): void {
    const listeners = keyListeners.get(key);
    if (!listeners) return;
    const val = get(store).get(key);
    for (const fn of listeners) fn(val);
  }

  function subscribeKey(cacheKey: string) {
    return readable<CacheEntry<TResult> | undefined>(get(store).get(cacheKey), (set) => {
      let s = keyListeners.get(cacheKey);
      if (!s) keyListeners.set(cacheKey, (s = new Set()));
      s.add(set);
      return () => {
        s!.delete(set);
        if (!s!.size) keyListeners.delete(cacheKey);
      };
    });
  }

  // inflight dedup
  const inflight = new Map<string, Promise<void>>();

  // batching
  const batchTimers = new Map<string, number>();
  const pendingBatch = new Map<string, Set<TInput>>();
  const lastRequestTime = new Map<string, number>();

  // pending by context (for not-found)
  const pendingByContext = new Map<string, Set<string>>();

  // sequences
  let currentSequence = 0;
  const contextSwitchSequences = new Map<string, number>();

  // stats
  let stats = {
    requests: 0,
    hits: 0,
    misses: 0,
    evictions: 0,
    totalProcessed: 0,
    responses: 0,
    lastUpdate: 0,
    found: 0,
    notFound: 0
  };

  function getNextSequence(contextId: string): number {
    return ++currentSequence;
  }

  function isValidSequence(contextId: string, seq: number): boolean {
    const minValidSeq = contextSwitchSequences.get(contextId) || 0;
    const valid = seq >= minValidSeq;
    if (!valid) {
      log.warn(`Ignoring stale sequence ${seq} for context ${contextId}, min valid: ${minValidSeq}`);
    }
    return valid;
  }

  function markPending(contextId: string, cacheKey: string): void {
    let s = pendingByContext.get(contextId);
    if (!s) pendingByContext.set(contextId, (s = new Set()));
    s.add(cacheKey);
  }

  function settlePending(contextId: string, resolvedKeys: Set<string>): Set<string> {
    const s = pendingByContext.get(contextId);
    if (!s) return new Set();
    const notFound = new Set<string>();
    for (const key of s) {
      if (resolvedKeys.has(key)) {
        s.delete(key);
      } else {
        notFound.add(key);
        s.delete(key);
      }
    }
    if (!s.size) pendingByContext.delete(contextId);
    return notFound;
  }

  function upsertKey(newCache: Map<string, CacheEntry<TResult>>, key: string, next: CacheEntry<TResult>): boolean {
    const prev = newCache.get(key);
    if (hooks.shallowEqualEntries(prev, next)) {
      return false;
    }
    newCache.set(key, next);
    return true;
  }

  function evictIfNeeded(cache: Map<string, CacheEntry<TResult>>, newEntriesCount: number): void {
    const currentSize = cache.size;
    const over = (currentSize + newEntriesCount) - config.limit;
    if (over <= 0) return;
    const entries = Array.from(cache.entries());
    entries.sort((a, b) => {
      const at = a[1]?.timestamp ?? 0;
      const bt = b[1]?.timestamp ?? 0;
      return at - bt;
    });
    let evicted = 0;
    for (let i = 0; i < entries.length && evicted < over; i++) {
      cache.delete(entries[i][0]);
      evicted++;
      stats.evictions++;
    }
  }

  function shouldProcessImmediately(prevLastRequest: number, now: number): boolean {
    const timeSince = now - prevLastRequest;
    const isFirst = prevLastRequest === 0;
    return isFirst || timeSince >= config.immediateThreshold;
  }

  function processBatchForContext(contextId: string, immediate: boolean): void {
    const items = pendingBatch.get(contextId);
    log.debug(`Processing batch for context="${contextId}" (immediate=${immediate}) with ${items ? items.size : 0} item(s)`);
    if (!items || items.size === 0) return;

    const inputs = Array.from(items);
    const seq = getNextSequence(contextId);

    // mark pending and update cache to pending state
    store.update(cache => {
      const newCache = new Map(cache);
      for (const input of inputs) {
        const key = hooks.makeCacheKey(input, contextId);
        markPending(contextId, key);
        if (!newCache.has(key) || newCache.get(key)?.status !== 'resolved') {
          const pendingInit = hooks.pendingInitializer ? hooks.pendingInitializer(input) : null;
          const entry: CacheEntry<TResult> = {
            result: pendingInit ?? undefined,
            status: 'pending',
            contextId,
            timestamp: Date.now(),
            accessCount: 1
          };
          newCache.set(key, entry);
          // notify only if new or changed
          setTimeout(() => notifyKey(key), 0);
        }
      }
      return newCache;
    });

    // send batch
    hooks.sendBatch(inputs, seq, contextId);

    // clear batch
    pendingBatch.delete(contextId);
    const timerId = batchTimers.get(contextId);
    if (timerId !== undefined) {
      clearTimeout(timerId);
      batchTimers.delete(contextId);
    }
  }

  function addToBatch(contextId: string, input: TInput): void {
    const now = Date.now();
    const prevLast = lastRequestTime.get(contextId) || 0;
    const isFirstRequest = !lastRequestTime.has(contextId);

    if (!pendingBatch.has(contextId)) {
      pendingBatch.set(contextId, new Set());
    }
    pendingBatch.get(contextId)!.add(input);

    const contextTimer = batchTimers.get(contextId);
    const immediate = shouldProcessImmediately(prevLast, now) && contextTimer === undefined;

    if (immediate) {
      processBatchForContext(contextId, true);
    } else if (contextTimer === undefined) {
      const delay = isFirstRequest ? 0 : config.batchDelay;
      const timerId = window.setTimeout(() => {
        processBatchForContext(contextId, false);
        batchTimers.delete(contextId);
      }, delay);
      batchTimers.set(contextId, timerId);
    }

    // update last after decision
    lastRequestTime.set(contextId, now);
  }

  async function performAtomicRequest(input: TInput, contextId: string, cacheKey: string): Promise<void> {
    return new Promise<void>((resolve) => {
      store.update(cache => {
        const existing = cache.get(cacheKey);
        if (existing?.status === 'resolved') {
          stats.hits++;
          resolve();
          return cache;
        }

        const newCache = new Map(cache);
        const pendingInit = hooks.pendingInitializer ? hooks.pendingInitializer(input) : null;
        const entry: CacheEntry<TResult> = {
          result: pendingInit ?? undefined,
          status: 'pending',
          contextId,
          timestamp: Date.now(),
          accessCount: 1
        };
        const updated = upsertKey(newCache, cacheKey, entry);
        if (updated) {
          setTimeout(() => notifyKey(cacheKey), 0);
        }

        stats.requests++;
        stats.misses++;
        stats.totalProcessed++;

        addToBatch(contextId, input);

        resolve();
        return newCache;
      });
    });
  }

  function parseResponseParams(seqOrContextId: number | string | undefined, contextId?: string): { contextId: string; sequence: number | null } {
    if (typeof seqOrContextId === 'string') {
      if (hooks.warnOnLegacyResponse) {
        log.warn('Received legacy response without sequence; consider updating producer to include sequence for proper validation');
      }
      return { contextId: seqOrContextId || defaultContextId, sequence: null };
    } else if (typeof seqOrContextId === 'number') {
      const sequence = seqOrContextId;
      const actualContextId = contextId || defaultContextId;
      if (!isValidSequence(actualContextId, sequence)) {
        throw new Error('Invalid sequence');
      }
      return { contextId: actualContextId, sequence };
    }
    return { contextId: defaultContextId, sequence: null };
  }

  // public api implementations

  function request(input: TInput, contextId: string = defaultContextId): Promise<void> {
    const cacheKey = hooks.makeCacheKey(input, contextId);
    if (inflight.has(cacheKey)) {
      return inflight.get(cacheKey)!;
    }
    const p = performAtomicRequest(input, contextId, cacheKey);
    inflight.set(cacheKey, p);
    p.finally(() => inflight.delete(cacheKey));
    return p;
  }

  function onResponse(results: Record<string, TResult>, seqOrContextId: number | string = defaultContextId, contextId?: string): void {
    try {
      const { contextId: ctx } = parseResponseParams(seqOrContextId, contextId);
      const keys = Object.keys(results);
      if (keys.length === 0) return;

      store.update(cache => {
        const newCache = new Map(cache);

        // estimate not-found count for eviction capacity (resolved + not-found)
        const resolvedSet = new Set<string>();
        for (const input of keys) {
          const key = hooks.makeCacheKey(input as unknown as TInput, ctx);
          resolvedSet.add(key);
        }

        let notFoundEstimate = 0;
        const pendingSet = pendingByContext.get(ctx);
        if (pendingSet && pendingSet.size > 0) {
          for (const key of pendingSet) {
            if (!resolvedSet.has(key)) {
              notFoundEstimate++;
            }
          }
        }

        evictIfNeeded(newCache, keys.length + notFoundEstimate);

        // apply resolved
        const keysToNotify: string[] = [];
        let updatedCount = 0;
        for (const input of keys) {
          const raw = results[input];
          const normalized = hooks.normalizeResolved ? hooks.normalizeResolved(raw) : raw;
          const key = hooks.makeCacheKey(input as unknown as TInput, ctx);

          const entry: CacheEntry<TResult> = {
            result: normalized,
            status: 'resolved',
            contextId: ctx,
            timestamp: Date.now(),
            accessCount: 1
          };

          const updated = upsertKey(newCache, key, entry);
          if (updated) {
            keysToNotify.push(key);
            updatedCount++;
            if (hooks.perfLog) hooks.perfLog(input as unknown as TInput, normalized);
          }
        }

        // mark not-found for remaining pendings
        const notFoundKeys = settlePending(ctx, resolvedSet);
        let notFoundCount = 0;
        for (const key of notFoundKeys) {
          const existing = newCache.get(key);
          const notFoundEntry = hooks.buildNotFoundEntry(existing);
          const updated = upsertKey(newCache, key, notFoundEntry);
          if (updated) {
            keysToNotify.push(key);
            notFoundCount++;
          }
        }

        stats.responses++;
        stats.lastUpdate = Date.now();
        stats.found += updatedCount;
        stats.notFound += notFoundCount;

        setTimeout(() => keysToNotify.forEach(notifyKey), 0);

        return newCache;
      });
    } catch {
      // stale / invalid sequence ignored
      return;
    }
  }

  function getEntry(input: TInput, contextId: string = defaultContextId): CacheEntry<TResult> | undefined {
    const key = hooks.makeCacheKey(input, contextId);
    const entry = get(store).get(key);
    if (entry) {
      const updated: CacheEntry<TResult> = { ...entry, accessCount: entry.accessCount + 1, timestamp: Date.now() };
      store.update(cache => {
        const newCache = new Map(cache);
        newCache.set(key, updated);
        return newCache;
      });
      return updated;
    }
    return entry;
  }

  function clearContextCache(contextId: string): void {
    store.update(cache => {
      const newCache = new Map(cache);
      const prefix = `${contextId}:`;
      for (const key of Array.from(newCache.keys())) {
        if (key.startsWith(prefix)) newCache.delete(key);
      }
      // note: we deliberately keep stats
      return newCache;
    });
  }

  function clearAll(): void {
    store.update(_ => {
      stats = { requests: 0, hits: 0, misses: 0, evictions: 0, totalProcessed: 0, responses: 0, lastUpdate: 0, found: 0, notFound: 0 };
      return new Map();
    });

    inflight.clear();
    pendingBatch.clear();
    pendingByContext.clear();

    for (const timer of batchTimers.values()) clearTimeout(timer);
    batchTimers.clear();
  }

  function getStats() {
    return { ...stats };
  }

  function getSize(): number {
    return get(store).size;
  }

  function getInflightCount(): number {
    return inflight.size;
  }

  function getContents(): Record<string, any> {
    const cache = get(store);
    const contents: Record<string, any> = {};
    for (const [key, entry] of cache.entries()) {
      contents[key] = {
        status: entry.status,
        contextId: entry.contextId,
        timestamp: entry.timestamp,
        accessCount: entry.accessCount,
        result: entry.result ?? null
      };
    }
    return contents;
  }

  function prepareContextSwitch(newContextId: string): void {
    const newSeq = getNextSequence(newContextId);
    contextSwitchSequences.set(newContextId, newSeq);
    log.debug(`Prepared context switch to ${newContextId} with sequence ${newSeq} as minimum valid`);
    lastRequestTime.delete(newContextId);

    // clear inflight for this context
    const toRemove: string[] = [];
    for (const key of inflight.keys()) {
      if (key.startsWith(`${newContextId}:`)) toRemove.push(key);
    }
    toRemove.forEach(k => inflight.delete(k));
  }

  function debugDump(): void {
    store.subscribe(cache => {
      log.debug('CACHE DEBUG - Total entries:', cache.size);
      for (const [key, entry] of cache.entries()) {
        log.debug(`  ${key}: status="${entry.status}" contextId="${entry.contextId}"`);
      }
    })();
  }

  return {
    store,
    subscribeKey,
    request,
    onResponse,
    getEntry,
    clearContextCache,
    clearAll,
    prepareContextSwitch,
    getStats,
    getSize,
    getInflightCount,
    getContents,
    debugDump
  };
}
