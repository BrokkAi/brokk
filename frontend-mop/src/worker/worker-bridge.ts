import type {InboundToWorker, OutboundFromWorker} from './shared';
import {reparseAll as reparseAllBubbles} from '../stores/bubblesStore';
import {reparseAll as reparseAllHistory} from '../stores/historyStore';
import {onWorkerResult} from './parseRouter';
import { createLogger } from '../lib/logging';

// Environment detection
const isDevMode = typeof window !== 'undefined' && !window.javaBridge;

console.log('MAIN: Creating worker with URL:', __WORKER_URL__);
const worker = new Worker(__WORKER_URL__, { type: 'module' });
console.log('MAIN: Worker created successfully');

// Expose worker globally for Java bridge access
(window as any).worker = worker;


const log = createLogger('worker-bridge');

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
    case 'log':
      window.javaBridge?.jsLog(msg.level, msg.message);
      break;
    case 'error':
      log.error('md-worker:', msg.message + '\n' + msg.stack);
      break;
    // Legacy case removed - symbol lookup now handled by reactive components
    case 'worker-log':
      const workerMsg = `${msg.message}`;
      switch (msg.level.toLowerCase()) {
        case 'error':
          log.debug(`[bridge] Received error from worker ${workerMsg}`);
          console.error(workerMsg);
          break;
        case 'warn':
          console.warn(workerMsg);
          break;
        case 'info':
          console.info(workerMsg);
          break;
        case 'debug':
        default:
          console.log(workerMsg);
          break;
      }
      break;
  }
};
