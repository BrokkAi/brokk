import {writable} from 'svelte/store';
import type {Root as HastRoot} from 'hast';

export type StaticDocState = {seq: number; text: string; tree?: HastRoot} | null;

export const staticDocStore = writable<StaticDocState>(null);