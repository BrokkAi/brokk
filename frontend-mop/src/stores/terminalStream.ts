import type { BubbleState } from '../types';

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
export function stripTerminalFenceAndComputeDelta(
    seq: number,
    rawChunk: string,
): { delta: string; complete: boolean } {
    const classified = classifyTerminalChunk(seq, rawChunk);
    if (classified.kind !== 'terminal') {
        return { delta: '', complete: false };
    }
    return { delta: classified.delta, complete: classified.complete };
}
