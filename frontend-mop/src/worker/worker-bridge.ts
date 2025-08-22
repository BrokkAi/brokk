import type {InboundToWorker, OutboundFromWorker} from './shared';
import { onWorkerResult, reparseAll } from '../stores/bubblesStore';
import { createLogger } from '../lib/logging';

// Environment detection
const isDevMode = typeof window !== 'undefined' && !window.javaBridge;

console.log('MAIN: Creating worker with URL:', __WORKER_URL__);
const worker = new Worker(__WORKER_URL__, { type: 'module' });
console.log('MAIN: Worker created successfully');

const log = createLogger('worker-bridge');

// Add worker error handling
worker.onerror = (error) => {
  console.error('MAIN: Worker error:', error);
  log.error('Worker error:', error);
};

worker.onmessageerror = (error) => {
  console.error('MAIN: Worker message error:', error);
  log.error('Worker message error:', error);
};

/* outbound ---------------------------------------------------------- */
export function pushChunk(text: string, seq: number) {
  // log.debugLog(`Sending chunk message to worker, seq: ${seq}`); // Too noisy
  worker.postMessage(<InboundToWorker>{ type: 'chunk', text, seq });
}

export function parse(text: string, seq: number, fast = false) {
  worker.postMessage(<InboundToWorker>{ type: 'parse', text, seq, fast });
}

export function clear(seq: number) {
  worker.postMessage(<InboundToWorker>{ type: 'clear', seq });
}

export function hideSpinner() {
  const contextId = getContextId();
  worker.postMessage(<InboundToWorker>{ type: 'hide-spinner', contextId });
}

export function expandDiff(markdown: string, bubbleId: number, blockId: string) {
  // 1. Ask worker to mark this block as "expanded"
  worker.postMessage(<InboundToWorker>{ type: 'expand-diff', bubbleId, blockId });
  // 2. Immediately trigger a slow parse for this single bubble
  worker.postMessage(<InboundToWorker>{
    type: 'parse',
    seq: bubbleId,
    text: markdown,
    fast: false
  });
}


/* context helper -------------------------------------------------- */
function getContextId(): string {
  try {
    const javaBridge = (window as any).javaBridge;
    if (javaBridge && typeof javaBridge.getContextCacheId === 'function') {
      return javaBridge.getContextCacheId();
    }
  } catch (e) {
    log.warn('Error getting context ID:', e);
  }
  return 'no-context';
}

/* symbol lookup handler ------------------------------------------- */
async function handleSymbolLookupRequest(symbols: string[], seq: number, contextId: string) {
  try {
    // Access JavaBridge from main window context
    const javaBridge = (window as any).javaBridge;
    if (!javaBridge || typeof javaBridge.lookupSymbols !== 'function') {
      log.warn('JavaBridge not available for symbol lookup');
      return;
    }

    // Log the lookup request
    log.info(`Processing ${symbols.length} symbols: ${symbols.join(', ')} for context: ${contextId}`);

    // Call JavaBridge to lookup symbols
    const jsonInput = JSON.stringify(symbols);
    log.debugLog(`[SYMBOL-LOOKUP] About to call javaBridge.lookupSymbols with: ${jsonInput}`);
    const resultsJson = javaBridge.lookupSymbols(jsonInput);
    log.debugLog(`[SYMBOL-LOOKUP] JavaBridge call completed`);

    // Log the results
    log.debugLog(`[SYMBOL-LOOKUP] Received results from JavaBridge: ${resultsJson}`);
    log.debugLog(`[SYMBOL-LOOKUP] Results type: ${typeof resultsJson}, length: ${resultsJson?.length}`);

    if (resultsJson) {
      log.debugLog(`[SYMBOL-LOOKUP] Parsing JSON results...`);
      const results = JSON.parse(resultsJson);
      log.debugLog(`[SYMBOL-LOOKUP] JSON parsed successfully, creating message...`);

      // Send results back to worker
      const message = <InboundToWorker>{
        type: 'symbol-lookup-response',
        results: results,
        seq: seq,
        contextId: contextId
      };
      log.debugLog(`[SYMBOL-LOOKUP] About to send message to worker: ${JSON.stringify(message)}`);
      try {
        worker.postMessage(message);
        log.debugLog(`[SYMBOL-LOOKUP] Sent symbol lookup response to worker for seq: ${seq}`);
      } catch (postError) {
        log.error(`[SYMBOL-LOOKUP] Error sending message to worker:`, postError);
      }
    } else {
      log.warn('No results received from JavaBridge');
    }
  } catch (e) {
    log.error('Error in symbol lookup:', e);
  }
}

/* inbound ----------------------------------------------------------- */
worker.onmessage = (e: MessageEvent<OutboundFromWorker>) => {
  const msg = e.data;

  switch (msg.type) {
    case 'shiki-langs-ready':
      log.debugLog(`[MAIN] Shiki processor ready (dev mode: ${isDevMode})`);
      reparseAll();
      // Notify Java side that processor is ready
      const javaBridge = (window as any).javaBridge;
      if (javaBridge && typeof javaBridge.onProcessorStateChanged === 'function') {
        javaBridge.onProcessorStateChanged('shiki-ready');
      }
      break;
    case 'result':
      log.debugLog(`[MAIN] Received result from worker for seq ${msg.seq}`);
      onWorkerResult(msg);
      break;
    case 'error':
      log.error('md-worker:', msg.message + '\n' + msg.stack);
      break;
    case 'symbol-lookup-request':
      //log.debugLog("++++++++ symbol");
      handleSymbolLookupRequest(msg.symbols, msg.seq, msg.contextId);
      break;
    case 'worker-log':
      // Use appropriate console method based on level for JavaFX WebView interception
      const workerMsg = `${msg.message}`;
      switch (msg.level.toLowerCase()) {
        case 'error':
          log.debugLog(`[bridge] Received error from worker ${workerMsg}`);
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
