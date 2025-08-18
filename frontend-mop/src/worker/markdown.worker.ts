import { initProcessor, parseMarkdown, handleSymbolLookupResponse } from './processor';
import type {
  InboundToWorker,
  OutboundFromWorker,
  ResultMsg,
  ErrorMsg,
} from './shared';
import { currentExpandIds } from './expand-state';
import { createLogger } from '../lib/logging';

const log = createLogger('md-worker-main');

// Worker logging helper
function workerLog(level: 'info' | 'warn' | 'error' | 'debug', message: string) {
  self.postMessage({ type: 'worker-log', level, message });
}

// Worker startup logging
workerLog('info', 'WORKER STARTUP: markdown.worker.ts loaded');
console.log('WORKER STARTUP: markdown.worker.ts loaded');

// Initialize the processor, which will asynchronously load Shiki.
initProcessor();

workerLog('info', 'WORKER STARTUP: processor initialized');
console.log('WORKER STARTUP: processor initialized');

let buffer = '';
let busy = false;
let dirty = false;
let seq = 0; // keeps echo of main-thread seq

self.onmessage = (ev: MessageEvent<InboundToWorker>) => {
  const m: InboundToWorker = ev.data;

  // Test multiple logging methods to see what works
  if (m.type !== 'chunk') {
    workerLog('info', `received message: ${m.type}`);
  }
  console.log(`CONSOLE.LOG: [WORKER] received: ${m.type}`);

  switch (m.type) {
    case 'parse':
      try {
        //set the buffer for the case that later the chunks are appended  (via messages of type 'chunk')
        buffer = m.text;
        const tree = parseMarkdown(m.seq, m.text, m.fast);
        post(<ResultMsg>{ type: 'result', tree, seq: m.seq });
      } catch (e) {
        log.error('processing error:', e);
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
      log.info('--- clear worker state ---')
      buffer = '';
      dirty = false;
      busy = false;  // Reset busy flag to prevent old parseAndPost from continuing
      seq = m.seq;
      currentExpandIds.clear();
      break;

    case 'expand-diff':
      currentExpandIds.add(m.blockId);
      // no parsing here – the main thread already sent a targeted parse
      break;

    case 'symbol-lookup-response':
      workerLog('info', `symbol-lookup-response received for seq ${m.seq} with ${Object.keys(m.results).length} symbols`);
      handleSymbolLookupResponse(m.seq, m.results);
      break;
  }
};

async function parseAndPost(seq: number): Promise<void> {
  try {
    // Force fast=false to ensure symbol lookup runs during streaming
    const tree = parseMarkdown(seq, buffer, false);
    post(<ResultMsg>{ type: 'result', tree, seq });
  } catch (e) {
    log.error('worker error:', e);
    const error = e instanceof Error ? e : new Error(String(e));
    post(<ErrorMsg>{ type: 'error', message: error.message, stack: error.stack, seq });
  }

  // this is needed to drain the event loop (queued message in onmessage) => accumulate some buffer
  await new Promise(r => setTimeout(r, 5));

  if (dirty) { dirty = false; await parseAndPost(seq); }
  else busy = false;
}

function post(msg: OutboundFromWorker) { self.postMessage(msg); }
