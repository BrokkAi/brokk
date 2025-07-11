import type {InboundToWorker, OutboundFromWorker} from './shared';
import { onWorkerResult, onShikiReady } from '../stores/bubblesStore';

const worker = new Worker('/markdown.worker.mjs', { type: 'module' });

/* outbound ---------------------------------------------------------- */
export function pushChunk(text: string, seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'chunk', text, seq });
}

export function parse(text: string, seq: number, fast = false) {
  worker.postMessage(<InboundToWorker>{ type: 'parse', text, seq, fast });
}

export function clear(seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'clear', seq });
}

/* inbound ----------------------------------------------------------- */
worker.onmessage = (e: MessageEvent<OutboundFromWorker>) => {
  const msg = e.data;

  switch (msg.type) {
    case 'shiki-langs-ready':
      onShikiReady();
      break;
    case 'result':
      onWorkerResult(msg);
      break;
    case 'error':
      console.error('[md-worker]', msg.message);
      break;
  }
};
