import { initProcessor, parseMarkdown, handleSymbolLookupResponse, clearSymbolCache, clearContextCache } from './processor';
import type {
  InboundToWorker,
  OutboundFromWorker,
  ResultMsg,
  ErrorMsg,
  LogMsg,
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
const ENABLE_WORKER_LOG = false;
initProcessor();


let buffer = '';
let seq = 0; // this represents the bubble id

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
      log('debug', '[md-worker] parse', m.seq, m.updateBuffer, m.text);
      // caller can decide whether to update internal buffer
      // "true" is only needed if chunks are added via append after the parse
      if (m.updateBuffer) {
        buffer = m.text;
        seq = m.seq;
      }
      safeParseAndPost(m.seq, m.text, m.fast);
      break;

    case 'chunk':
      log('debug', '[md-worker] chunk', m.seq, m.text);
      buffer += m.text;
      seq = m.seq;
      processChunk(); // Simple immediate processing
      break;

    case 'clear-state':
      log('debug', '[md-worker] clear-state', seq, m.flushBeforeClear);
      // Final flush of any pending buffer for the previous stream/message
      // Do not flush on hard clear
      if (m.flushBeforeClear && buffer.length > 0) {
        safeParseAndPost(seq, buffer);
      }
      buffer = '';
      seq = 0;
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


function processChunk(): void {
  // Simple immediate processing of current buffer during streaming
  safeParseAndPost(seq, buffer, true); // Always fast=true for streaming chunks
}

function post(msg: OutboundFromWorker) { self.postMessage(msg); }

function safeParseAndPost(seq: number, text: string, fast: boolean = false) {
  try {
    // Caller controls symbol lookup behavior via fast parameter
    const tree = parseMarkdown(seq, text, fast);
    post(<ResultMsg>{ type: 'result', tree, seq });
  } catch (e) {
    log('error', '[md-worker]', e);
    const error = e instanceof Error ? e : new Error(String(e));
    post(<ErrorMsg>{ type: 'error', message: error.message, stack: error.stack, seq: seq });
  }
}

function log(level: LogMsg['level'], ...args: unknown[]) {
  if (!ENABLE_WORKER_LOG) {
    return;
  }
  const message = args
      .map(arg => {
        if (arg instanceof Error) {
          return arg.stack || arg.message;
        }
        if (typeof arg === 'object' && arg !== null) {
          return JSON.stringify(arg);
        }
        return String(arg);
      })
      .join(' ');
  post(<LogMsg>{ type: 'log', level, message });
}
