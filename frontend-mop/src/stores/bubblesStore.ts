import { writable } from 'svelte/store';
import type {BrokkEvent, Bubble, BubbleState} from "../types";
import type {ResultMsg} from "../worker/shared";
import {clear, pushChunk, parse} from "../worker/worker-bridge";

export const bubblesStore = writable<BubbleState[]>([]);

/* ─── monotonic IDs & seq  ───────────────────────────── */
let nextBubbleId = 0;   // grows forever (DOM keys never reused)

/* ─── main entry from Java bridge ─────────────────────── */
export function onBrokkEvent(evt: BrokkEvent): void {
  bubblesStore.update(list => {
    switch (evt.type) {
      case 'clear':
        nextBubbleId++;
        clear(nextBubbleId);
        return [];

      case 'chunk': {
        const isStreaming = evt.streaming ?? false;
        // Decide if we append or start a new bubble
        const needNew = evt.isNew ||
                        list.length === 0 ||
                        evt.msgType !== list[list.length - 1].type;

        let bubble: BubbleState;
        if (needNew) {
          nextBubbleId++;
          bubble = {
            id: nextBubbleId,
            type: evt.msgType!,
            markdown: evt.text ?? '',
            epoch: evt.epoch,
            streaming: isStreaming
          };
          list = [...list, bubble];
          if (isStreaming) {
            clear(bubble.id);
          }
        } else {
          bubble = list[list.length - 1]!;
          bubble.markdown += evt.text ?? '';
          bubble.epoch = evt.epoch;
          bubble.streaming = isStreaming;
        }

        if (isStreaming) {
          pushChunk(evt.text ?? '', bubble.id);
        } else {
          parse(bubble.markdown, bubble.id);
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
    const bubble = list.find(b => b.id === msg.seq);
    if (bubble) bubble.hast = msg.tree;
    return [...list];
  });
}
