export type Seq = number;

/* ---------- main → worker ---------- */
export interface ChunkMsg { type: 'chunk'; text: string; seq: Seq; }
export interface ClearMsg { type: 'clear'; seq: Seq; }
export interface ParseMsg { type: 'parse'; text: string; seq: Seq; fast: boolean; }
export type InboundToWorker = ChunkMsg | ClearMsg | ParseMsg;

/* ---------- worker → main ---------- */
import type { Root as HastRoot } from 'hast';
export interface ResultMsg { type: 'result'; tree: HastRoot; seq: Seq; }
export interface ErrorMsg { type: 'error'; message: string; seq: Seq; }
export interface ShikiReadyMsg { type: 'shiki-ready'; }
export type OutboundFromWorker = ResultMsg | ErrorMsg | ShikiReadyMsg;
