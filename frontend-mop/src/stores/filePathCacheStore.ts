import { createLogger } from '../lib/logging';
import { createBatchedCache, type CacheEntry } from './batchedCache';

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

// Public cache entry type for callers
export type FilePathCacheEntry = CacheEntry<FilePathLookupResult>;

const log = createLogger('file-path-cache-store');

const CONFIG = {
  immediateThreshold: 100,
  batchDelay: 15,
  limit: 1000,
  loggerName: 'file-path-cache-store'
} as const;

// Domain hooks
function makeCacheKey(input: string, contextId: string): string {
  return `${contextId}:${input}`;
}

function shallowEqualEntries(a: FilePathCacheEntry | undefined, b: FilePathCacheEntry | undefined): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  if (a.status !== b.status || a.contextId !== b.contextId) return false;
  const aExists = a.result?.exists;
  const bExists = b.result?.exists;
  const aMatchCount = a.result?.matches?.length || 0;
  const bMatchCount = b.result?.matches?.length || 0;
  return aExists === bExists && aMatchCount === bMatchCount;
}

function sendBatch(inputs: string[], seq: number, contextId: string): void {
  if (window.javaBridge?.lookupFilePathsAsync) {
    window.javaBridge.lookupFilePathsAsync(JSON.stringify(inputs), seq, contextId);
  }
}

function normalizeResolved(result: FilePathLookupResult): FilePathLookupResult {
  const filtered = (result.matches || []).filter(m => !m.isDirectory);
  return {
    ...result,
    exists: filtered.length > 0,
    matches: filtered
  };
}

function buildNotFoundEntry(existing?: FilePathCacheEntry): FilePathCacheEntry {
  return {
    result: {
      exists: false,
      matches: [],
      confidence: 0,
      processingTimeMs: 0
    },
    status: 'resolved',
    contextId: existing?.contextId || 'main-context',
    timestamp: Date.now(),
    accessCount: 1
  };
}

function perfLog(input: string, result: FilePathLookupResult): void {
  if (typeof result.processingTimeMs === 'number') {
    const found = result.exists ? 'found' : 'not found';
    const matchCount = result.matches?.length || 0;
    log.debug(`[PERF][FRONTEND] FilePath '${input}' ${found} (${matchCount} matches, ${result.confidence}% confidence, ${result.processingTimeMs}ms)`);
  }
}

// Create cache
const cache = createBatchedCache<string, FilePathLookupResult>(
  CONFIG,
  {
    makeCacheKey,
    shallowEqualEntries,
    sendBatch,
    normalizeResolved,
    buildNotFoundEntry,
    perfLog,
    warnOnLegacyResponse: true
  },
  'main-context'
);

// Public API mirroring previous exports
export const filePathCacheStore = cache.store;
export const subscribeKey = cache.subscribeKey;

export function requestFilePathResolution(filePath: string, contextId: string = 'main-context'): Promise<void> {
  return cache.request(filePath, contextId);
}

export function onFilePathResolutionResponse(results: Record<string, FilePathLookupResult>, seqOrContextId: number | string = 'main-context', contextId?: string): void {
  cache.onResponse(results, seqOrContextId, contextId);
}

export function getFilePathCacheEntry(filePath: string, contextId: string = 'main-context'): FilePathCacheEntry | undefined {
  return cache.getEntry(filePath, contextId);
}

export function clearContextCache(contextId: string): void {
  cache.clearContextCache(contextId);
}

export function clearFilePathCache(): void {
  cache.clearAll();
}

export function getCacheStats() {
  const s = cache.getStats();
  // keep compat field names
  return {
    requests: s.requests,
    hits: s.hits,
    misses: s.misses,
    evictions: s.evictions,
    totalFilePathsProcessed: s.totalProcessed,
    responses: s.responses,
    lastUpdate: s.lastUpdate,
    filePathsFound: s.found,
    filePathsNotFound: s.notFound
  };
}

export function getCacheSize(): number {
  return cache.getSize();
}

export function getInflightRequestsCount(): number {
  return cache.getInflightCount();
}

export function getCacheContents(): Record<string, any> {
  // Expand matches metadata to preserve previous shape for dev tools
  const contents = cache.getContents();
  for (const key of Object.keys(contents)) {
    const entry = contents[key];
    if (entry?.result) {
      entry.result = {
        exists: entry.result.exists,
        matchCount: entry.result.matches?.length || 0,
        confidence: entry.result.confidence,
        processingTimeMs: entry.result.processingTimeMs,
        matches: (entry.result.matches || []).map((m: ProjectFileMatch) => ({
          relativePath: m.relativePath,
          absolutePath: m.absolutePath,
          isDirectory: m.isDirectory,
          lineNumber: m.lineNumber,
          lineRange: m.lineRange
        }))
      };
    }
  }
  return contents;
}

export function prepareContextSwitch(newContextId: string): void {
  cache.prepareContextSwitch(newContextId);
}

export function debugFilePathCache(): void {
  cache.debugDump();
}
