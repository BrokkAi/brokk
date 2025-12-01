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
    return { ...store, [threadId]: entry };
  });
}

export function updateSummaryParseTree(threadId: number, tree: ResultMsg['tree']): void {
  summaryParseStore.update(store => {
    if (store[threadId]) {
      return { ...store, [threadId]: { ...store[threadId], tree } };
    }
    return store;
  });
}

export function deleteSummaryParseEntry(threadId: number): void {
  summaryParseStore.update(store => {
    const { [threadId]: _, ...rest } = store;
    return rest;
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
