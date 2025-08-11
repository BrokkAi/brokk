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

export type InboundToWorker = ChunkMsg | ClearMsg | ParseMsg | ExpandDiffMsg;

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

export type OutboundFromWorker = ResultMsg | ErrorMsg | ShikiLangsReadyMsg;

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
