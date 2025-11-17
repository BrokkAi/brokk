import {writable} from 'svelte/store';
import type {BrokkEvent, BubbleState} from '../types';
import type {ResultMsg} from '../worker/shared';
import {clearState, pushChunk, parse} from '../worker/worker-bridge';
import {register, unregister, isRegistered} from '../worker/parseRouter';
import { getNextThreadId, threadStore } from './threadStore';

export const bubblesStore = writable<BubbleState[]>([]);

/* ─── monotonic IDs & seq  ───────────────────────────── */
let nextBubbleSeq = 0;   // grows forever (DOM keys never reused)
let currentThreadId = getNextThreadId();
threadStore.setThreadCollapsed(currentThreadId, false, 'live');

/* ─── main entry from Java bridge ─────────────────────── */
export function onBrokkEvent(evt: BrokkEvent): void {
    // console.debug('Received event in onBrokkEvent:', evt.type);
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
                    const updatedBubble = finalizeReasoningBubble(lastBubble);
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

                // Register a handler for this bubble's parse results (only once per seq)
                if (!isRegistered(bubble.seq)) {
                    register(bubble.seq, (msg: ResultMsg) => {
                        bubblesStore.update(list => {
                            const i = list.findIndex(b => b.seq === msg.seq);
                            if (i === -1) return list;
                            const next = list.slice();
                            next[i] = { ...next[i], hast: msg.tree };
                            return next;
                        });
                    });
                }
                if (isStreaming) {
                    pushChunk(evt.text ?? '', bubble.seq);
                } else {
                    // first fast pass (to show fast results), then deferred full pass
                    parse(bubble.markdown, bubble.seq, true, true);
                    setTimeout(() => {
                        if (isRegistered(bubble.seq)) {
                            parse(bubble.markdown, bubble.seq, false, true);
                        }
                    }, 20);
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
                bubblesStore.update(list => {
                    const i = list.findIndex(b => b.seq === msg.seq);
                    if (i === -1) return list;
                    const next = list.slice();
                    next[i] = { ...next[i], hast: msg.tree };
                    return next;
                });
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

/* ─── helpers ─────────────────────────────────────────── */
function finalizeReasoningBubble(b: BubbleState): BubbleState {
    if (!b.reasoning) return b;
    const durationInMs = b.startTime ? Date.now() - b.startTime : 0;
    return {
        ...b,
        streaming: false,
        reasoningComplete: true,
        duration: durationInMs / 1000,
        isCollapsed: true,
    };
}

/**
 * Track live task progress. On end (inProgress=false), finalize all bubbles:
 * stop streaming; for reasoning bubbles, mark complete, set duration, and collapse.
 */
export function setLiveTaskInProgress(inProgress: boolean): void {
    if (inProgress) return; // nothing to do on start; bubbles will stream as chunks arrive

    bubblesStore.update(list => {
        return list.map(b => {
            let updated = b;
            if (b.streaming) {
                updated = {...updated, streaming: false};
            }
            if (b.reasoning && !b.reasoningComplete) {
                updated = finalizeReasoningBubble(updated);
            }
            return updated;
        });
    });
}

/**
 * Delete an entire live thread by its threadId:
 * - Unregister parse handlers for all bubbles in the thread
 * - Drop all bubbles belonging to that thread
 * - Reset worker buffer and rotate a fresh live thread id (mirrors 'clear' behavior)
 * - Notify backend to drop the last history entry (-1)
 */
export function deleteLiveTaskByThreadId(threadId: number): void {
    bubblesStore.update(list => {
        const toRemove = list.filter(b => b.threadId === threadId);
        if (toRemove.length === 0) {
            return list;
        }

        // Unregister parsers for removed bubbles
        toRemove.forEach(b => unregister(b.seq));

        // If deleting current live thread, reset live state similarly to 'clear'
        if (threadId === currentThreadId) {
            nextBubbleSeq++; // maintain strictly increasing DOM keys across resets
            clearState(false); // hard clear
            threadStore.clearThreadsByType('live');
            currentThreadId = getNextThreadId();
            threadStore.setThreadCollapsed(currentThreadId, false, 'live');
        }

        // Ask backend to remove the last entry in history (the just-finished live task)
        window.javaBridge?.deleteHistoryTask?.(-1);

        // no optimistic UI update needed; backend will send history-reset event
        return list;
    });
}
