import type {InboundToWorker, OutboundFromWorker, ResultMsg} from './shared';
import { onWorkerResult } from '../stores/bubblesStore';

const worker = new Worker('/markdown.worker.mjs', { type: 'module' });

/* outbound ---------------------------------------------------------- */
export function pushChunk(text: string, seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'chunk', text, seq });
}

export function parse(text: string, seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'parse', text, seq });
}

export function clear(seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'clear', seq });
}

/* inbound ----------------------------------------------------------- */
worker.onmessage = (e: MessageEvent<OutboundFromWorker>) => {
  const msg = e.data;
  if (msg.type === 'error') {
    console.error('[md-worker]', msg.message);
  }
  onWorkerResult(e.data as ResultMsg);
};
