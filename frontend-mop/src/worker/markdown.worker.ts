import { initProcessor, parseMarkdown } from './processor';
import type {
  InboundToWorker,
  OutboundFromWorker,
  ResultMsg,
  ErrorMsg,
} from './shared';
import { currentExpandIds } from './expand-state';

// Initialize the processor, which will asynchronously load Shiki.
initProcessor();

let buffer = '';
let busy = false;
let dirty = false;
let seq = 0; // this represents the bubble id

self.onmessage = (ev: MessageEvent<InboundToWorker>) => {
  const m: InboundToWorker = ev.data;
  switch (m.type) {
    case 'parse':
      buffer = m.text;
      safeParseAndPost(m.seq, m.text, m.fast);
      break;

    case 'chunk':
      buffer += m.text;
      seq = m.seq;
      if (!busy) { busy = true; void parseAndPost(); }
      else dirty = true;
      break;

    case 'clear':
      console.log('--- clear worker state ---');
      // Final flush of any pending buffer for the previous stream/message
      if (buffer.length > 0) {
        safeParseAndPost(seq, buffer);
      }
      buffer = '';
      dirty = false;
      busy = false; // Stop any in-flight parseAndPost loops
      seq = m.seq;
      currentExpandIds.clear();
      break;

    case 'expand-diff':
      currentExpandIds.add(m.blockId);
      // no parsing here – the main thread already sent a targeted parse
      break;
  }
};

async function parseAndPost(): Promise<void> {
  // Capture the sequence number for this run. This acts as a token to detect
  // if the context has changed (e.g., a new message has started) during the async pause.
  const seqForThisRun = seq;

  safeParseAndPost(seqForThisRun, buffer);

  // Yield to the event loop to allow more chunks to buffer up.
  await new Promise(r => setTimeout(r, 5));

  // Cancellation Guard: If the global seq has changed, it means a `clear`
  // message for a new bubble has arrived. This loop is now stale and must terminate.
  if (seqForThisRun !== seq) {
    // The `clear` handler will have already set `busy = false`, allowing a new
    // loop to start for the new bubble. We just need to stop this one.
    return;
  }

  if (dirty) {
    dirty = false;
    await parseAndPost(); // Recurse
  } else {
    busy = false;
  }
}

function post(msg: OutboundFromWorker) { self.postMessage(msg); }

function safeParseAndPost(seq: number, text: string, fast: boolean = false) {
  try {
    const tree = parseMarkdown(seq, text, fast);
    post(<ResultMsg>{ type: 'result', tree, seq: seq });
  } catch (e) {
    console.error('[md-worker]', e);
    const error = e instanceof Error ? e : new Error(String(e));
    post(<ErrorMsg>{ type: 'error', message: error.message, stack: error.stack, seq: seq });
  }
}