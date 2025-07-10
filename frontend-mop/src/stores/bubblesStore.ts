import { writable } from 'svelte/store';
import type {BrokkEvent, Bubble, BubbleState} from "../types";
import type {ResultMsg} from "../worker/shared";
import {clear, flush, pushChunk} from "../worker/worker-bridge";

export const bubblesStore = writable<BubbleState[]>([]);

/* ─── monotonic IDs & seq  ───────────────────────────── */
let nextBubbleId = 0;   // grows forever (DOM keys never reused)
let currentSeq = 0;     // grows on each AI bubble reset

/* ─── main entry from Java bridge ─────────────────────── */
export function onBrokkEvent(evt: BrokkEvent): void {
  bubblesStore.update(list => {
    switch (evt.type) {
      case 'clear':
        currentSeq += 1;
        clear(currentSeq);
        return [];

      case 'chunk': {
        // Decide if we append or start a new bubble
        const needNew = evt.isNew ||
                        list.length === 0 ||
                        evt.msgType !== list[list.length - 1].type;

        if (needNew) {
          currentSeq += 1;
          clear(currentSeq);
          list = [...list, {
            id: nextBubbleId++,
            type: evt.msgType!,
            markdown: evt.text ?? '',
            seq: currentSeq,
            epoch: evt.epoch,
            streaming: evt.streaming ?? false
          }];
        } else {
          const last = list[list.length - 1]!;
          last.markdown += evt.text ?? '';
          last.epoch = evt.epoch;
          last.streaming = evt.streaming ?? false;
        }

        pushChunk(evt.text ?? '', currentSeq);
        if (evt.streaming === false) {
          flush(currentSeq);
        }
        return [...list];
      }

      default:
        return list;
    }
  });
}

/* ─── entry from worker ───────────────────────────────── */
export function onWorkerResult(msg: ResultMsg): void {
  bubblesStore.update(list => {
    const bubble = list.find(b => b.seq === msg.seq);
    if (bubble) bubble.hast = msg.tree;
    return [...list];
  });
}
