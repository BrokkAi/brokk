import { writable } from 'svelte/store';
import type { ResultMsg } from '../worker/shared';

export type SummaryParseEntry = {
  seq: number;
  text: string;
  tree?: ResultMsg['tree'];
};

export const summaryParseStore = writable<Record<number, SummaryParseEntry>>({});

export function setSummaryParseEntry(threadId: number, entry: SummaryParseEntry): void {
  summaryParseStore.update(store => {
    store[threadId] = entry;
    return store;
  });
}

export function updateSummaryParseTree(threadId: number, tree: ResultMsg['tree']): void {
  summaryParseStore.update(store => {
    if (store[threadId]) {
      store[threadId].tree = tree;
    }
    return store;
  });
}

export function deleteSummaryParseEntry(threadId: number): void {
  summaryParseStore.update(store => {
    delete store[threadId];
    return store;
  });
}

export function clearAllSummaryParseEntries(): void {
  summaryParseStore.set({});
}

export function getSummaryParseEntry(threadId: number): SummaryParseEntry | undefined {
  let result: SummaryParseEntry | undefined;
  summaryParseStore.subscribe(store => {
    result = store[threadId];
  })();
  return result;
}
