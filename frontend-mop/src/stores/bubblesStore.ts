import {writable} from 'svelte/store';
import type {BrokkEvent, BubbleState} from '../types';
import type {ResultMsg} from '../worker/shared';
import {clearState, pushChunk, parse} from '../worker/worker-bridge';
import {register, unregister} from '../worker/parseRouter';
import { getNextThreadId, threadStore } from './threadStore';

export const bubblesStore = writable<BubbleState[]>([]);

/* ─── monotonic IDs & seq  ───────────────────────────── */
let nextBubbleSeq = 0;   // grows forever (DOM keys never reused)
let currentThreadId = getNextThreadId();
threadStore.setThreadCollapsed(currentThreadId, false, 'live');

/* ─── main entry from Java bridge ─────────────────────── */
export function onBrokkEvent(evt: BrokkEvent): void {
    console.log('Received event in onBrokkEvent:', JSON.stringify(evt));
    bubblesStore.update(list => {
        switch (evt.type) {
            case 'clear':
                list.forEach(bubble => unregister(bubble.seq));
                nextBubbleSeq++;
                // clear without flushing (hard clear; no next message)
                clearState(false);
                threadStore.clearThreadsByType('live');
                currentThreadId = getNextThreadId();
                threadStore.setThreadCollapsed(currentThreadId, false, 'live');
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
                        isCollapsed: true, // Auto-collapse reasoning bubble
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
                        threadId: currentThreadId,
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

                // Register a handler for this bubble's parse results
                register(bubble.seq, (msg: ResultMsg) => {
                    bubblesStore.update(l =>
                        l.map(b => (b.seq === msg.seq ? {...b, hast: msg.tree} : b))
                    );
                });
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
export function reparseAll(contextId = 'main-context'): void {
    bubblesStore.update(list => {
        for (const bubble of list) {
            // Re-register a handler for each bubble. This overwrites any existing handler
            // for the same seq, so there is no need to unregister first.
            register(bubble.seq, (msg: ResultMsg) => {
                bubblesStore.update(l =>
                    l.map(b => (b.seq === msg.seq ? {...b, hast: msg.tree} : b))
                );
            });
            // Re-parse any bubble that has markdown content and might contain code.
            // skip updating the internal worker buffer, to give the worker the chance to go ahead where it stopped after reparseAll
            parse(bubble.markdown, bubble.seq, false, false);
        }
        return list; // Return new list with cleared HAST to trigger reactivity
    });
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
