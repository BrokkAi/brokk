export const HISTORY_MSG_SEQ_START = 1_000_000;
export const SUMMARY_SEQ_START = 2_000_000;

let nextHistoryMsgSeq = HISTORY_MSG_SEQ_START;
let nextSummarySeq = SUMMARY_SEQ_START;

export function allocHistoryMsgSeq(): number {
    return nextHistoryMsgSeq++;
}

export function allocSummarySeq(): number {
    return nextSummarySeq++;
}

export function isHistorySeq(seq: number | undefined): boolean {
    return typeof seq === 'number' && seq >= HISTORY_MSG_SEQ_START && seq < SUMMARY_SEQ_START;
}

export function isSummarySeq(seq: number | undefined): boolean {
    return typeof seq === 'number' && seq >= SUMMARY_SEQ_START;
}
