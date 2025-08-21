import {writable} from 'svelte/store';
import type {BrokkEvent, BubbleState} from '../types';
import type {ResultMsg} from '../worker/shared';
import {clearState, pushChunk, parse} from '../worker/worker-bridge';

export const bubblesStore = writable<BubbleState[]>([]);

/* ─── monotonic IDs & seq  ───────────────────────────── */
let nextBubbleSeq = 0;   // grows forever (DOM keys never reused)

/* ─── main entry from Java bridge ─────────────────────── */
export function onBrokkEvent(evt: BrokkEvent): void {
    console.log('Received event in onBrokkEvent:', JSON.stringify(evt));
    bubblesStore.update(list => {
        switch (evt.type) {
            case 'clear':
                nextBubbleSeq++;
                // clear without flushing (hard clear; no next message)
                clearState(false);
                return [];

            case 'chunk': {
                const lastBubble = list.at(-1);
                // If the last message was a streaming reasoning bubble and the new one is not,
                // mark the reasoning as complete, immutably.
                if (lastBubble?.reasoning && !lastBubble.reasoningComplete && !evt.reasoning) {
                    const durationInMs = lastBubble.startTime ? Date.now() - lastBubble.startTime : 0;
                    const updatedBubble: BubbleState = {
                        ...lastBubble,
                        reasoningComplete: true,
                        streaming: false,
                        duration: durationInMs / 1000,
                        isCollapsed: true, // Auto-collapse
                    };
                    list = [...list.slice(0, -1), updatedBubble];
                }

                const isStreaming = evt.streaming ?? false;
                // Decide if we append or start a new bubble
                const needNew = evt.isNew ||
                    list.length === 0 ||
                    evt.msgType !== lastBubble?.type ||
                    evt.reasoning !== (lastBubble?.reasoning ?? false);


                let bubble: BubbleState;
                if (needNew) {
                    nextBubbleSeq++;
                    bubble = {
                        seq: nextBubbleSeq,
                        type: evt.msgType ?? 'AI',
                        markdown: evt.text ?? '',
                        epoch: evt.epoch,
                        streaming: isStreaming,
                        reasoning: evt.reasoning ?? false,
                    };
                    if (bubble.reasoning) {
                        bubble.startTime = Date.now();
                        bubble.reasoningComplete = false;
                        bubble.isCollapsed = false;
                    }
                    list = [...list, bubble];
                    if (isStreaming) {
                        // clear with flush (boundary for next message)
                        clearState(true);
                    }
                } else {
                    // Immutable update
                    const last = list.at(-1)!;
                    bubble = {
                        ...last,
                        markdown: last.markdown + (evt.text ?? ''),
                        epoch: evt.epoch,
                        streaming: isStreaming,
                    };
                    list = [...list.slice(0, -1), bubble];
                }

                if (isStreaming) {
                    pushChunk(evt.text ?? '', bubble.seq);
                } else {
                    // first fast pass (to show fast results), then deferred full pass
                    parse(bubble.markdown, bubble.seq, true);
                    setTimeout(() => parse(bubble.markdown, bubble.seq), 0);
                }
                return list;
            }

            default:
                return list;
        }
    });
}

/* ─── entry from worker ───────────────────────────────── */
export function reparseAll(): void {
    bubblesStore.update(list => {
        for (const bubble of list) {
            // Re-parse any bubble that has markdown content and might contain code.
            // skip updating the internal worker buffer, to give the worker the chance to go ahead where it stopped after reparseAll
            parse(bubble.markdown, bubble.seq, false);
        }
        return list; // The list reference itself does not change
    });
}

export function onWorkerResult(msg: ResultMsg): void {
    bubblesStore.update(list =>
        list.map(b => (b.seq === msg.seq ? {...b, hast: msg.tree} : b))
    );
}

/* ─── UI actions ──────────────────────────────────────── */
export function toggleBubbleCollapsed(seq: number): void {
    bubblesStore.update(list => {
        return list.map(bubble => {
            if (bubble.seq === seq) {
                return {...bubble, isCollapsed: !bubble.isCollapsed};
            }
            return bubble;
        });
    });
}
