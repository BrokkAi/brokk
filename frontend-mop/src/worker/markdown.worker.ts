/// <reference lib="webworker" />

import type { Root as HastRoot } from 'hast';
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
let seq = 0; // keeps echo of main-thread seq

self.onmessage = (ev: MessageEvent<InboundToWorker>) => {
  const m: InboundToWorker = ev.data;
  switch (m.type) {
    case 'parse':
      try {
        const tree = parseMarkdown(m.seq, m.text, m.fast);
        post(<ResultMsg>{ type: 'result', tree, seq: m.seq });
      } catch (e) {
        post(<ErrorMsg>{ type: 'error', message: String(e), seq: m.seq });
      }
      break;

    case 'chunk':
      buffer += m.text;
      seq = m.seq;
      if (!busy) { busy = true; void parseAndPost(m.seq); }
      else dirty = true;
      break;

    case 'clear':
      buffer = '';
      dirty = false;
      seq = m.seq;
      currentExpandIds.clear();
      break;

    case 'expand-diff':
      currentExpandIds.add(m.blockId);
      // no parsing here – the main thread already sent a targeted parse
      break;
  }
};

async function parseAndPost(seq: number): Promise<void> {
  try {
    const tree = parseMarkdown(seq, buffer);
    post(<ResultMsg>{ type: 'result', tree, seq });
  } catch (e) {
    post(<ErrorMsg>{ type: 'error', message: String(e), seq });
  }

  // this is needed to drain the event loop (queued message in onmessage) => accumulate some buffer
  await new Promise(r => setTimeout(r, 5));

  if (dirty) { dirty = false; await parseAndPost(seq); }
  else busy = false;
}

function post(msg: OutboundFromWorker) { self.postMessage(msg); }
