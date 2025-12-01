import { writable } from 'svelte/store';

export type LiveSummaryEntry = {
  compressed: boolean;
  summary: string;
};

export const liveSummaryStore = writable<Record<number, LiveSummaryEntry>>({});

export function setLiveSummary(threadId: number, entry: LiveSummaryEntry): void {
  liveSummaryStore.update(store => {
    return { ...store, [threadId]: entry };
  });
}

export function deleteLiveSummary(threadId: number): void {
  liveSummaryStore.update(store => {
    const { [threadId]: _, ...rest } = store;
    return rest;
  });
}

export function clearAllLiveSummaries(): void {
  liveSummaryStore.set({});
}

export function getLiveSummary(threadId: number): LiveSummaryEntry | undefined {
  let result: LiveSummaryEntry | undefined;
  liveSummaryStore.subscribe(store => {
    result = store[threadId];
  })();
  return result;
}
