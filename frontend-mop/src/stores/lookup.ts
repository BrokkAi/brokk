import { get } from 'svelte/store';
import { bubblesStore } from './bubblesStore';
import { historyStore } from './historyStore';
import type { BubbleState } from '../types';

export function findBubbleBySeq(seq: number): BubbleState | undefined {
  const live = get(bubblesStore).find(b => b.seq === seq);
  if (live) return live;

  const tasks = get(historyStore);
  for (const task of tasks) {
    const match = task.entries.find(e => e.seq === seq);
    if (match) return match;
  }
  return undefined;
}

export function findMarkdownBySeq(seq: number): string | undefined {
  return findBubbleBySeq(seq)?.markdown;
}
