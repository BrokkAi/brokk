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
    results: Record<string, {exists: boolean}>;
    seq: Seq;
}

export type InboundToWorker = ChunkMsg | ClearMsg | ParseMsg | ExpandDiffMsg | SymbolLookupResponseMsg;

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
}

export interface SymbolLookupRequestMsg {
    type: 'symbol-lookup-request';
    symbols: string[];
    seq: Seq;
}

export interface WorkerLogMsg {
    type: 'worker-log';
    level: 'info' | 'warn' | 'error' | 'debug';
    message: string;
}

export type OutboundFromWorker = ResultMsg | ErrorMsg | ShikiLangsReadyMsg | SymbolLookupRequestMsg | WorkerLogMsg;

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
