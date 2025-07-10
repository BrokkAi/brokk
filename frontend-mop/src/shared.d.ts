export type Seq = number;

/* ---------- main → worker ---------- */
export interface ChunkMsg { type: 'chunk'; text: string; seq: Seq; }
export interface ClearMsg { type: 'clear'; seq: Seq; }
export interface FlushMsg { type: 'flush'; seq: Seq; }
export type InboundToWorker = ChunkMsg | ClearMsg | FlushMsg;

/* ---------- worker → main ---------- */
import type { Root as HastRoot } from 'hast';
export interface ResultMsg { type: 'result'; tree: HastRoot; seq: Seq; }
export interface ErrorMsg { type: 'error'; message: string; seq: Seq; }
export type OutboundFromWorker = ResultMsg | ErrorMsg;
