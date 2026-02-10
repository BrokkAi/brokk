import { writable } from 'svelte/store';
import { createLogger } from '../lib/logging';
import { createBatchedCache, type CacheEntry } from './batchedCache';
import { mockExtractSymbol } from '../dev/mockSymbolExtractor';

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
  confidence?: number;
  processingTimeMs?: number;
}

// Public cache entry type for callers
export type SymbolCacheEntry = CacheEntry<SymbolLookupResult>;

const log = createLogger('symbol-cache-store');

// Refresh trigger store - increment to signal all components to re-detect symbols
export const symbolRefreshTrigger = writable(0);

const CONFIG = {
  immediateThreshold: 100,
  batchDelay: 15,
  limit: 1000,
  loggerName: 'symbol-cache-store'
} as const;

function makeCacheKey(input: string, contextId: string): string {
  return `${contextId}:${input}`;
}

function shallowEqualEntries(a: SymbolCacheEntry | undefined, b: SymbolCacheEntry | undefined): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  if (a.status !== b.status || a.contextId !== b.contextId) return false;
  const aFqn = a.result?.fqn ?? null;
  const bFqn = b.result?.fqn ?? null;
  return aFqn === bFqn;
}

function sendBatch(inputs: string[], seq: number, contextId: string): void {
  if (window.javaBridge?.lookupSymbolsAsync) {
    window.javaBridge.lookupSymbolsAsync(JSON.stringify(inputs), seq, contextId);
  }
}

function buildNotFoundEntry(existing?: SymbolCacheEntry): SymbolCacheEntry {
  const prior = existing?.result;
  const result: SymbolLookupResult | null = prior
    ? { ...prior, fqn: null }
    : null;

  return {
    result,
    status: 'resolved',
    contextId: existing?.contextId || 'main-context',
    timestamp: Date.now(),
    accessCount: 1
  };
}

function perfLog(input: string, result: SymbolLookupResult): void {
  if (typeof result.processingTimeMs === 'number') {
    const found = result.fqn ? 'found' : 'not found';
    const matchType = result.isPartialMatch ? 'partial' : 'exact';
    const confidence = result.confidence ?? 0;
    log.debug(`[PERF][FRONTEND] Symbol '${input}' ${found} (${matchType}, ${confidence}% confidence, ${result.processingTimeMs}ms)`);
  }
}

// Create cache
const cache = createBatchedCache<string, SymbolLookupResult>(
  CONFIG,
  {
    makeCacheKey,
    pendingInitializer: (sym) => mockExtractSymbol(sym) as unknown as SymbolLookupResult | null,
    shallowEqualEntries,
    sendBatch,
    buildNotFoundEntry,
    perfLog,
    warnOnLegacyResponse: false
  },
  'main-context'
);

// Public API mirroring previous exports
export const symbolCacheStore = cache.store;
export const subscribeKey = cache.subscribeKey;

export function requestSymbolResolution(symbol: string, contextId: string = 'main-context'): Promise<void> {
  return cache.request(symbol, contextId);
}

export function onSymbolResolutionResponse(results: Record<string, SymbolLookupResult>, seqOrContextId: number | string = 'main-context', contextId?: string): void {
  cache.onResponse(results, seqOrContextId, contextId);
}

export function getSymbolCacheEntry(symbol: string, contextId: string = 'main-context'): SymbolCacheEntry | undefined {
  return cache.getEntry(symbol, contextId);
}

export function clearContextCache(contextId: string): void {
  cache.clearContextCache(contextId);
  // Trigger refresh for all components
  symbolRefreshTrigger.update(n => n + 1);
}

export function clearSymbolCache(): void {
  cache.clearAll();
  // Trigger refresh for all components
  symbolRefreshTrigger.update(n => n + 1);
}

export function getCacheStats() {
  const s = cache.getStats();
  // keep compat field names
  return {
    requests: s.requests,
    hits: s.hits,
    misses: s.misses,
    evictions: s.evictions,
    totalSymbolsProcessed: s.totalProcessed,
    responses: s.responses,
    lastUpdate: s.lastUpdate,
    symbolsFound: s.found,
    symbolsNotFound: s.notFound
  };
}

export function getCacheSize(): number {
  return cache.getSize();
}

export function getInflightRequestsCount(): number {
  return cache.getInflightCount();
}

export function getCacheContents(): {
  entries: Array<{
    cacheKey: string;
    symbol: string;
    contextId: string;
    status: string;
    fqn: string | null;
    isPartialMatch: boolean;
    originalText: string | null;
    highlightRanges: Array<{ start: number, end: number }>;
    timestamp: number;
    accessCount: number;
  }>;
  stats: ReturnType<typeof getCacheStats>;
  inflightRequests: string[];
  timestamp: string;
} {
  const contents = cache.getContents();
  const entries = Object.entries(contents).map(([cacheKey, entry]) => {
    const symbol = cacheKey.split(':').slice(1).join(':');
    const result = entry.result as SymbolLookupResult | null;
    return {
      cacheKey,
      symbol,
      contextId: entry.contextId,
      status: entry.status,
      fqn: result?.fqn ?? null,
      isPartialMatch: result?.isPartialMatch ?? false,
      originalText: result?.originalText ?? null,
      highlightRanges: result?.highlightRanges ?? [],
      timestamp: entry.timestamp,
      accessCount: entry.accessCount
    };
  });

  return {
    entries: entries.sort((a, b) => b.timestamp - a.timestamp),
    stats: getCacheStats(),
    inflightRequests: [],
    timestamp: new Date().toISOString()
  };
}

export function debugSymbolCache(): void {
  cache.debugDump();
}

export function prepareContextSwitch(newContextId: string): void {
  cache.prepareContextSwitch(newContextId);
}
