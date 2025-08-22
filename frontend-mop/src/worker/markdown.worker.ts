import { initProcessor, parseMarkdown, handleSymbolLookupResponse, clearSymbolCache, clearContextCache } from './processor';
import type {
  InboundToWorker,
  OutboundFromWorker,
  ResultMsg,
  ErrorMsg,
} from './shared';
import { currentExpandIds } from './expand-state';
import { createWorkerLogger } from '../lib/logging';

const workerLog = createWorkerLogger('md-worker-main');

// Global error handlers for uncaught errors and promise rejections
self.onerror = (event) => {
  const errorMsg = `[markdown-worker] ${event.message} at ${event.filename}:${event.lineno}:${event.colno}`;
  workerLog.error(errorMsg);
  return true;
};

self.onunhandledrejection = (event) => {
  const errorMsg = `[markdown-worker] ${event.reason}`;
  workerLog.error(errorMsg + (event.reason?.stack ? `\n${event.reason.stack}` : ''));
  event.preventDefault();
};

// Worker startup logging
workerLog.info('Worker Startup: markdown.worker.ts loaded');

// Initialize the processor, which will asynchronously load Shiki.
initProcessor();


let buffer = '';
let busy = false;
let dirty = false;
let seq = 0; // keeps echo of main-thread seq

self.onmessage = (ev: MessageEvent<InboundToWorker>) => {
  try {
    const m: InboundToWorker = ev.data;

    // Validate message structure
    if (!m || typeof m !== 'object' || !m.type) {
      workerLog.error(`[markdown-worker] Invalid message structure: ${JSON.stringify(m)}`);
      return;
    }

    // Test multiple logging methods to see what works
    if (m.type !== 'chunk') {
      workerLog.debug(`[markdown-worker] received: ${m.type}`);
    }

  switch (m.type) {
    case 'parse':
      try {
        //set the buffer for the case that later the chunks are appended  (via messages of type 'chunk')
        buffer = m.text;
        const tree = parseMarkdown(m.seq, m.text, m.fast);
        post(<ResultMsg>{ type: 'result', tree, seq: m.seq });
      } catch (e) {
        console.error('processing error:', e);
        const error = e instanceof Error ? e : new Error(String(e));
        post(<ErrorMsg>{ type: 'error', message: error.message, stack: error.stack, seq: m.seq });
      }
      break;

    case 'chunk':
      buffer += m.text;
      seq = m.seq;
      if (!busy) { busy = true; void parseAndPost(m.seq); }
      else dirty = true;
      break;

    case 'clear':
      console.log('--- clear worker state ---')
      buffer = '';
      dirty = false;
      busy = false;  // Reset busy flag to prevent old parseAndPost from continuing
      seq = m.seq;
      currentExpandIds.clear();
      clearSymbolCache();
      break;

    case 'expand-diff':
      currentExpandIds.add(m.blockId);
      // no parsing here – the main thread already sent a targeted parse
      break;

    case 'symbol-lookup-response':
      workerLog.info(`symbol-lookup-response received for seq ${m.seq} with ${Object.keys(m.results).length} symbols`);
      handleSymbolLookupResponse(m.seq, m.results, m.contextId);
      break;


    case 'hide-spinner':
      if (m.contextId) {
        workerLog.info(`Spinner hidden - clearing symbol cache for context: ${m.contextId}`);
        clearContextCache(m.contextId);
      } else {
        workerLog.info('Spinner hidden - clearing all symbol cache');
        clearSymbolCache();
      }
      break;
  }
  } catch (error) {
    workerLog.error(`[WORKER-MESSAGE] Error processing message: ${error.message}\n${error.stack}`);
  }
};

function parseAndPost(seq: number): void {
  try {
    // Force fast=false to ensure symbol lookup runs during streaming
    const tree = parseMarkdown(seq, buffer, false);
    post(<ResultMsg>{ type: 'result', tree, seq });
  } catch (e) {
    console.error('worker error:', e);
    const error = e instanceof Error ? e : new Error(String(e));
    post(<ErrorMsg>{ type: 'error', message: error.message, stack: error.stack, seq });
  }

  // this is needed to drain the event loop (queued message in onmessage) => accumulate some buffer
  setTimeout(() => {
    if (dirty) { dirty = false; parseAndPost(seq); }
    else busy = false;
  }, 5);
}

function post(msg: OutboundFromWorker) { self.postMessage(msg); }
