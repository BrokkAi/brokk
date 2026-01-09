import {writable} from 'svelte/store';
import type {BrokkEvent, BubbleState} from '../types';
import type {ResultMsg} from '../worker/shared';
import {clearState, pushChunk, parse} from '../worker/worker-bridge';
import {register, unregister, isRegistered} from '../worker/parseRouter';
import { getNextThreadId, threadStore } from './threadStore';
import { deleteSummaryEntry, getSummaryEntry } from './summaryStore';
import { hideTransientMessage } from './transientStore';

export const bubblesStore = writable<BubbleState[]>([]);

/* ─── monotonic IDs & seq  ───────────────────────────── */
let nextBubbleSeq = 0;   // grows forever (DOM keys never reused)
let currentThreadId = getNextThreadId();
threadStore.setThreadCollapsed(currentThreadId, false, 'live');

export type TerminalDetectionState = {
    /** Small, leading buffer used only to detect an opening ```terminal\n at the very start. */
    detectBuffer: string;
    /** True once this bubble has been identified as a terminal stream. */
    isTerminal: boolean;
    /** True once the closing ``` fence has been observed (even if followed by trailing text). */
    complete: boolean;
    /** Tail buffer to detect a closing ``` fence that may arrive split across chunks. */
    closingBuffer: string;
};

const terminalDetectionBySeq = new Map<number, TerminalDetectionState>();

export const TERMINAL_OPEN_FENCE = '```terminal\n';
export const TERMINAL_CLOSE_FENCE = '```';

export function getOrCreateTerminalDetectionState(seq: number): TerminalDetectionState {
    const existing = terminalDetectionBySeq.get(seq);
    if (existing) return existing;
    const created: TerminalDetectionState = {
        detectBuffer: '',
        isTerminal: false,
        complete: false,
        closingBuffer: '',
    };
    terminalDetectionBySeq.set(seq, created);
    return created;
}

export function resetTerminalDetectionState(seq: number): void {
    terminalDetectionBySeq.delete(seq);
}

export function appendTerminalDelta(prev: BubbleState, delta: string): BubbleState {
    const existingChunks = prev.terminalChunks ?? [];
    const nextChunks = delta.length === 0 ? existingChunks : [...existingChunks, delta];
    return {
        ...prev,
        isTerminal: true,
        terminalChunks: nextChunks,
        markdown: nextChunks.join(''),
    };
}

export type TerminalClassification =
    | { kind: 'undecided' }
    | { kind: 'notTerminal'; flushPrefix: string }
    | { kind: 'terminal'; delta: string; complete: boolean };

/**
 * Classify a raw incoming chunk for a given seq.
 *
 * - undecided: still matching a prefix of TERMINAL_OPEN_FENCE; buffer bytes and emit nothing
 * - notTerminal: cannot match; flush all buffered bytes back to normal markdown path (includes rawChunk)
 * - terminal: confirmed; emit terminal delta while tracking closing fence across chunk boundaries
 */
export function classifyTerminalChunk(seq: number, rawChunk: string): TerminalClassification {
    const state = getOrCreateTerminalDetectionState(seq);

    if (state.complete) {
        return { kind: 'terminal', delta: '', complete: true };
    }

    if (!state.isTerminal) {
        state.detectBuffer += rawChunk;

        if (state.detectBuffer.startsWith(TERMINAL_OPEN_FENCE)) {
            state.isTerminal = true;
            state.detectBuffer = state.detectBuffer.slice(TERMINAL_OPEN_FENCE.length);
        } else if (TERMINAL_OPEN_FENCE.startsWith(state.detectBuffer)) {
            return { kind: 'undecided' };
        } else {
            const flushPrefix = state.detectBuffer;
            resetTerminalDetectionState(seq);
            return { kind: 'notTerminal', flushPrefix };
        }
    } else {
        state.detectBuffer += rawChunk;
    }

    // Terminal mode: strip closing fence that may arrive split across chunks.
    const text = state.closingBuffer + state.detectBuffer;
    state.detectBuffer = '';
    state.closingBuffer = '';

    const idx = text.indexOf(TERMINAL_CLOSE_FENCE);
    if (idx >= 0) {
        state.complete = true;
        return { kind: 'terminal', delta: text.slice(0, idx), complete: true };
    }

    // Keep up to 2 chars buffered to detect a future ``` split across boundaries.
    const keep = Math.min(TERMINAL_CLOSE_FENCE.length - 1, text.length);
    state.closingBuffer = text.slice(text.length - keep);
    return { kind: 'terminal', delta: text.slice(0, text.length - keep), complete: false };
}

/**
 * Backwards-compatible wrapper for existing call sites that already know they're in terminal mode.
 * If classification returns undecided or notTerminal, no terminal bytes should be emitted.
 */
export function stripTerminalFenceAndComputeDelta(seq: number, rawChunk: string): { delta: string; complete: boolean } {
    const classified = classifyTerminalChunk(seq, rawChunk);
    if (classified.kind !== 'terminal') {
        return { delta: '', complete: false };
    }
    return { delta: classified.delta, complete: classified.complete };
}

/* ─── main entry from Java bridge ─────────────────────── */
export function onBrokkEvent(evt: BrokkEvent): void {
    // console.debug('Received event in onBrokkEvent:', evt.type);
    bubblesStore.update(list => {
        switch (evt.type) {
            case 'clear':
                list.forEach(bubble => {
                    unregister(bubble.seq);
                    resetTerminalDetectionState(bubble.seq);
                });
                // Clean up live summary for the current thread before clearing
                const prevSummaryEntry = getSummaryEntry(currentThreadId);
                if (prevSummaryEntry && isRegistered(prevSummaryEntry.seq)) {
                    unregister(prevSummaryEntry.seq);
                }
                deleteSummaryEntry(currentThreadId);
                nextBubbleSeq++;
                // clear without flushing (hard clear; no next message)
                clearState(false);
                threadStore.clearThreadsByType('live');
                currentThreadId = getNextThreadId();
                threadStore.setThreadCollapsed(currentThreadId, false, 'live');
                return [];

            case 'chunk': {
                hideTransientMessage();
                const lastBubble = list.at(-1);

                // If the last message was a streaming reasoning bubble and the new one is not,
                // mark the reasoning as complete, immutably.
                if (lastBubble?.reasoning && !lastBubble.reasoningComplete && !evt.reasoning) {
                    const updatedBubble = finalizeReasoningBubble(lastBubble);
                    list = [...list.slice(0, -1), updatedBubble];
                }

                const isStreaming = evt.streaming ?? false;
                const chunkText = evt.text ?? '';

                // Decide if we append or start a new bubble
                const needNew = evt.isNew ||
                    list.length === 0 ||
                    evt.msgType !== lastBubble?.type ||
                    evt.reasoning !== (lastBubble?.reasoning ?? false) ||
                    // If the last bubble is terminal, keep appending streaming chunks to it until it completes.
                    // This prevents splitting a single terminal stream across multiple bubbles while preserving
                    // the terminal vs non-terminal separation contract.
                    ((lastBubble?.isTerminal ?? false) &&
                        ((lastBubble?.terminalComplete ?? false) || !(lastBubble?.streaming ?? false)));

                let bubble: BubbleState;
                if (needNew) {
                    nextBubbleSeq++;
                    resetTerminalDetectionState(nextBubbleSeq);
                    bubble = {
                        seq: nextBubbleSeq,
                        threadId: currentThreadId,
                        type: evt.msgType ?? 'AI',
                        markdown: '',
                        epoch: evt.epoch,
                        streaming: isStreaming,
                        reasoning: evt.reasoning ?? false,
                        isTerminal: false,
                        terminalChunks: [],
                        terminalComplete: false,
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
                    bubble = list.at(-1)!;
                }

                // Terminal classification must happen before we decide where bytes go.
                const classification = bubble.isTerminal
                    ? ({ kind: 'terminal', ...stripTerminalFenceAndComputeDelta(bubble.seq, chunkText) } as const)
                    : classifyTerminalChunk(bubble.seq, chunkText);

                if (classification.kind === 'undecided') {
                    // Buffer only; do not append any bytes to markdown or worker.
                    return list;
                }

                if (classification.kind === 'terminal') {
                    const current = list.at(-1)!;
                    let updated: BubbleState = {
                        ...current,
                        isTerminal: true,
                        hast: undefined,
                    };

                    updated = appendTerminalDelta(updated, classification.delta);
                    updated = {
                        ...updated,
                        terminalComplete: classification.complete || updated.terminalComplete || !isStreaming,
                        streaming: isStreaming && !classification.complete,
                        markdown: (updated.terminalChunks ?? []).join(''),
                        epoch: evt.epoch,
                    };

                    list = [...list.slice(0, -1), updated];

                    if (!isStreaming || classification.complete) {
                        resetTerminalDetectionState(updated.seq);
                    }

                    return list;
                }

                // notTerminal: flush buffered prefix back into normal markdown path so no bytes are lost.
                const flushedText = classification.flushPrefix;

                // Update bubble markdown with flushed bytes; then proceed with normal worker flow.
                const current = list.at(-1)!;
                bubble = {
                    ...current,
                    markdown: current.markdown + flushedText,
                    epoch: evt.epoch,
                    streaming: isStreaming,
                };
                list = [...list.slice(0, -1), bubble];

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
                    pushChunk(flushedText, bubble.seq);
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
            if (bubble.isTerminal) {
                unregister(bubble.seq);
                resetTerminalDetectionState(bubble.seq);
                continue;
            }

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
        return list;
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
 * Get the current live thread ID.
 * This is used when processing live summaries that may arrive before bubbles are created.
 */
export function getCurrentLiveThreadId(): number {
    return currentThreadId;
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
            if (b.isTerminal) {
                updated = {...updated, terminalComplete: true};
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
        toRemove.forEach(b => {
            unregister(b.seq);
        });

        // Clean up live summary for the deleted thread
        const summaryEntry = getSummaryEntry(threadId);
        if (summaryEntry && isRegistered(summaryEntry.seq)) {
            unregister(summaryEntry.seq);
        }
        deleteSummaryEntry(threadId);

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
