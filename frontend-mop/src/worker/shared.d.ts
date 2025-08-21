export type Seq = number;


/* ---------- main → worker ---------- */
export interface ChunkMsg {
    type: 'chunk';
    text: string;
    seq: Seq;
}

export interface ClearMsg {
    type: 'clear';
    seq: Seq;
}

export interface ParseMsg {
    type: 'parse';
    text: string;
    seq: Seq;
    fast: boolean;
}

export interface ExpandDiffMsg {
    type: 'expand-diff';
    blockId: string;   // <edit-block data-id="…">
    bubbleId: number;  // owning bubble
}

export interface SymbolLookupResponseMsg {
    type: 'symbol-lookup-response';
    results: Record<string, {exists: boolean, fqn?: string | null}>;
    seq: Seq;
    contextId: string;
}

export interface TestErrorMsg {
    type: 'test-error';
    errorType: 'uncaughtError' | 'promiseRejection' | 'syntaxError';
}

export interface HideSpinnerMsg {
    type: 'hide-spinner';
    contextId?: string;
}

export type InboundToWorker = ChunkMsg | ClearMsg | ParseMsg | ExpandDiffMsg | SymbolLookupResponseMsg | TestErrorMsg | HideSpinnerMsg;

/* ---------- worker → main ---------- */
import type {Root as HastRoot} from 'hast';

export interface ResultMsg {
    type: 'result';
    tree: HastRoot;
    seq: Seq;
}

export interface ErrorMsg {
    type: 'error';
    message: string;
    stack?: string;
    seq: Seq;
}

export interface ShikiLangsReadyMsg {
    type: 'shiki-langs-ready';
    canHighlight?: string[]; // languages now available
}

export interface SymbolLookupRequestMsg {
    type: 'symbol-lookup-request';
    symbols: string[];
    seq: Seq;
    contextId: string;
}

export interface WorkerLogMsg {
    type: 'worker-log';
    level: 'info' | 'warn' | 'error' | 'debug';
    message: string;
}

export interface ProcessorStateChangeMsg {
    type: 'processor-state-changed';
    state: 'base-ready' | 'shiki-initializing' | 'shiki-ready';
}

export type OutboundFromWorker = ResultMsg | ErrorMsg | ShikiLangsReadyMsg | SymbolLookupRequestMsg | WorkerLogMsg | ProcessorStateChangeMsg;

// shared by both

export interface EditBlockProperties {
    bubbleId: number;
    id: string;
    isExpanded: boolean;
    adds?: number;
    dels?: number;
    filename?: string;
    search?: string;
    replace?: string;
    headerOk: boolean;
}
