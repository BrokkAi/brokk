import type {InboundToWorker, OutboundFromWorker} from './shared';
import { onWorkerResult, reparseAll } from '../stores/bubblesStore';

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

export function expandDiff(markdown: string, bubbleId: number, blockId: string) {
  // 1. Ask worker to mark this block as “expanded”
  worker.postMessage(<InboundToWorker>{ type: 'expand-diff', bubbleId, blockId });
  // 2. Immediately trigger a slow parse for this single bubble
  worker.postMessage(<InboundToWorker>{
    type: 'parse',
    seq: bubbleId,
    text: markdown,
    fast: false
  });
}

/* inbound ----------------------------------------------------------- */
worker.onmessage = (e: MessageEvent<OutboundFromWorker>) => {
  const msg = e.data;

  switch (msg.type) {
    case 'shiki-langs-ready':
      reparseAll();
      break;
    case 'result':
      onWorkerResult(msg);
      break;
    case 'error':
      console.error('[md-worker]', msg.message);
      break;
  }
};
