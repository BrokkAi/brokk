import { writable } from 'svelte/store';
import type { ResultMsg } from '../worker/shared';

export type SummaryParseEntry = {
  seq: number;
  text: string;
  compressed?: boolean;
  tree?: ResultMsg['tree'];
};

export const summaryStore = writable<Record<number, SummaryParseEntry>>({});

export function setSummaryEntry(threadId: number, entry: SummaryParseEntry): void {
  summaryStore.update(store => {
    return { ...store, [threadId]: entry };
  });
}

export function updateSummaryTree(threadId: number, tree: ResultMsg['tree']): void {
  summaryStore.update(store => {
    if (store[threadId]) {
      return { ...store, [threadId]: { ...store[threadId], tree } };
    }
    return store;
  });
}

export function deleteSummaryEntry(threadId: number): void {
  summaryStore.update(store => {
    const { [threadId]: _, ...rest } = store;
    return rest;
  });
}

export function clearAllSummaryEntries(): void {
  summaryStore.set({});
}

export function getSummaryEntry(threadId: number): SummaryParseEntry | undefined {
  let result: SummaryParseEntry | undefined;
  summaryStore.subscribe(store => {
    result = store[threadId];
  })();
  return result;
}
