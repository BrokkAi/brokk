/// <reference lib="webworker" />

import { parseMarkdown } from './parse-markdown';
import type {
  InboundToWorker, OutboundFromWorker,
  ResultMsg, ErrorMsg
} from './shared';

let buffer = '';
let busy = false;
let dirty = false;
let seq = 0; // keeps echo of main-thread seq

self.onmessage = (ev: MessageEvent<InboundToWorker>) => {
  const m = ev.data;
  switch (m.type) {
    case 'chunk':
      buffer += m.text;
      seq = m.seq;
      if (!busy) { busy = true; void parseAndPost(); }
      else dirty = true;
      break;

    case 'clear':
      buffer = '';
      dirty = false;
      seq = m.seq;
      break;

    case 'flush':
      seq = m.seq;
      if (!busy) { busy = true; void parseAndPost(); }
      break;
  }
};

async function parseAndPost(): Promise<void> {
  try {
    const tree = parseMarkdown(buffer);
    post(<ResultMsg>{ type: 'result', tree, seq });
  } catch (e) {
    post(<ErrorMsg>{ type: 'error', message: String(e), seq });
  }

  // this is needed to drain the event loop (queued message in onmessage) => accumulate some buffer
  await new Promise(r => setTimeout(r, 5));

  if (dirty) { dirty = false; await parseAndPost(); }
  else busy = false;
}

function post(msg: OutboundFromWorker) { self.postMessage(msg); }
