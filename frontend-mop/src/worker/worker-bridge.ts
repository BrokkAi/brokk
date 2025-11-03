import type {InboundToWorker, OutboundFromWorker} from './shared';
import {reparseAll as reparseAllBubbles} from '../stores/bubblesStore';
import {reparseAll as reparseAllHistory} from '../stores/historyStore';
import {onWorkerResult} from './parseRouter';
import { createLogger } from '../lib/logging';

/* Environment detection */
const isDevMode = typeof window !== 'undefined' && !window.javaBridge;

/* Tagged logger for this module (declare early for consistent logging) */
const log = createLogger('worker-bridge');

log.info('MAIN: Creating worker with URL:', __WORKER_URL__);
const worker = new Worker(__WORKER_URL__, { type: 'module' });
log.info('MAIN: Worker created successfully');

// Expose worker globally for Java bridge access
(window as any).worker = worker;

/* outbound ---------------------------------------------------------- */
export function pushChunk(text: string, seq: number) {
  // log.debug(`Sending chunk message to worker, seq: ${seq}`); // Too noisy
  worker.postMessage(<InboundToWorker>{ type: 'chunk', text, seq });
}

export function parse(text: string, seq: number, fast = false, updateBuffer = true) {
  worker.postMessage(<InboundToWorker>{ type: 'parse', text, seq, fast, updateBuffer });
}

export function clearState(flushBeforeClear: boolean) {
  worker.postMessage(<InboundToWorker>{ type: 'clear-state', flushBeforeClear });
}

export function expandDiff(markdown: string, bubbleId: number, blockId: string) {
  // 1. Ask worker to mark this block as "expanded"
  worker.postMessage(<InboundToWorker>{ type: 'expand-diff', bubbleId, blockId });
  // 2. Immediately trigger a slow parse for this single bubble
  worker.postMessage(<InboundToWorker>{
    type: 'parse',
    seq: bubbleId,
    text: markdown,
    fast: false,
    updateBuffer: false  // Don't overwrite worker's internal buffer state
  });
}

/* context helper -------------------------------------------------- */
function getContextId(): string {
  // Use constant context ID
  return 'main-context';
}

/* inbound ----------------------------------------------------------- */
worker.onmessage = (e: MessageEvent<OutboundFromWorker>) => {
  const msg = e.data;

  switch (msg.type) {
    case 'shiki-langs-ready':
      reparseAllBubbles();
      reparseAllHistory();
      break;
    case 'result':
      onWorkerResult(msg);
      break;
    case 'worker-log':
      switch (msg.level) {
        case 'error':
          log.error(msg.message);
          break;
        case 'warn':
          log.warn(msg.message);
          break;
        case 'info':
          log.info(msg.message);
          break;
        case 'debug':
          log.debug(msg.message);
          break;
      }
      break;
    case 'error':
      log.error('md-worker:', msg.message + '\n' + msg.stack);
      break;
  }
};
