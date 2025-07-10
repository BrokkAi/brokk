import type {InboundToWorker, OutboundFromWorker, ResultMsg} from './shared';
import { onWorkerResult } from './bubblesStore';

const worker = new Worker('/markdown.worker.mjs', { type: 'module' });

/* outbound ---------------------------------------------------------- */
export function pushChunk(text: string, seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'chunk', text, seq });
}

export function clear(seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'clear', seq });
}

export function flush(seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'flush', seq });
}

/* inbound ----------------------------------------------------------- */
worker.onmessage = (e: MessageEvent<OutboundFromWorker>) => {
  const msg = e.data;
  if (msg.type === 'error') {
    console.error('[md-worker]', msg.message);
  }
  onWorkerResult(e.data as ResultMsg);
};
