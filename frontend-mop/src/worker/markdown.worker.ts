import {initProcessor, parseMarkdown} from './processor';
import type {ErrorMsg, InboundToWorker, OutboundFromWorker, ResultMsg} from './shared';
import {currentExpandIds, userCollapsedIds} from './expand-state';
import {createWorkerLogger} from '../lib/logging';

const workerLogger = createWorkerLogger('markdown-worker');

// Global error handlers for uncaught errors and promise rejections
self.onerror = (event) => {
    const message = event.message || 'Unknown error';
    const filename = event.filename || 'unknown';
    const lineno = event.lineno || 0;
    const colno = event.colno || 0;

    if (event.error) {
        unhandledError('[markdown-worker]', event.error.message || message, 'at', `${filename}:${lineno}:${colno}`, event.error.stack);
    } else {
        unhandledError('[markdown-worker]', message, 'at', `${filename}:${lineno}:${colno}`);
    }
    return true;
};

self.onunhandledrejection = (event) => {
    const reason = event.reason || 'Unknown rejection';
    const stack = event.reason?.stack;
    unhandledError('[markdown-worker]', reason, stack);
    event.preventDefault();
};

workerLogger.info('Worker Startup: markdown.worker.ts loaded');
initProcessor();

let buffer = '';
let busy = false;
let dirty = false;
let seq = 0; // this represents the bubble id
let runEpoch = 0; // incremented only on 'clear-state' to invalidate queued-but-stale parse requests

self.onmessage = (ev: MessageEvent<InboundToWorker>) => {
    const m: InboundToWorker = ev.data;
    switch (m.type) {
        case 'parse':
            // workerLogger.debug('parse', m.seq, m.updateBuffer, m.text);
            if (m.updateBuffer) {
                buffer = m.text;
                seq = m.seq;
            }
            // Capture current epoch for this run; only clear-state advances the epoch
            const epochForThisRun = beginRun();
            // Yield to allow immediate superseding messages to bump the epoch; skip if stale
            void safeParseAndPostEpoch(m.seq, m.text, m.fast, epochForThisRun);
            break;

        case 'chunk':
            // workerLogger.debug('chunk', m.seq, m.text);
            // Assert invariant: chunks for a different seq must not arrive while streaming another seq
            // (a clear-state should separate streams). Log if this happens to catch integration issues.
            if (busy && seq !== 0 && m.seq !== seq) {
                workerLogger.error(
                    'Interleaved chunk seq without clear-state: current seq=' +
                    String(seq) + ', incoming seq=' + String(m.seq) +
                    ', runEpoch=' + String(runEpoch)
                );
            }

            buffer += m.text;
            seq = m.seq;
            if (!busy) {
                busy = true;
                void parseAndPost();
            } else dirty = true;
            break;

        case 'clear-state':
            // m.flushBeforeClear => true means new message in the current session; false means clear
            if (m.flushBeforeClear && buffer.length > 0) {
                // Intentionally flush before bumping the epoch to honor explicit flush requests
                safeParseAndPost(seq, buffer);
            } else {
                // only clear edit block expansion state when clearing/ new session
                currentExpandIds.clear();
                userCollapsedIds.clear();
            }
            // Invalidate any queued parse requests after the clear
            runEpoch++;
            buffer = '';
            dirty = false;
            busy = false;
            seq = 0;
            break;

        case 'expand-diff':
            // User or auto wants this block expanded: clear any remembered collapse and mark expanded
            userCollapsedIds.delete(m.blockId);
            currentExpandIds.add(m.blockId);
            break;

        case 'collapse-diff':
            // User explicitly collapsed this block: remember it and ensure it won't be auto-expanded
            currentExpandIds.delete(m.blockId);
            userCollapsedIds.add(m.blockId);
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

    if (seqForThisRun !== seq) {
        // this happens when clean-state is processed when draining events
        // clean-state flushes the buffer for this seq and reset dirty/busy
        workerLogger.debug('cancel guard: seqForThisRun !== seq', seqForThisRun, seq);
        return;
    }

    if (dirty) {
        dirty = false;
        await parseAndPost(); // Recurse
    } else {
        busy = false;
    }
}

function post(msg: OutboundFromWorker) {
    self.postMessage(msg);
}

function safeParseAndPost(seq: number, text: string, fast: boolean = false) {
    try {
        const tree = parseMarkdown(seq, text, fast);
        post(<ResultMsg>{type: 'result', tree, seq: seq});
    } catch (e) {
        workerLogger.error('[md-worker]', e);
        const error = e instanceof Error ? e : new Error(String(e));
        post(<ErrorMsg>{type: 'error', message: error.message, stack: error.stack, seq: seq});
    }
}

/**
 * Marks the beginning of a parse "run".
 * Note: Only 'clear-state' advances runEpoch so that parallel parses for different seqs
 * are not invalidated by each other.
 */
function beginRun(): number {
    return runEpoch;
}

/**
 * Asynchronous parse with epoch checks and a macrotask yield to allow superseding messages
 * (e.g., clear-state or newer parse) to invalidate this run before heavy work starts.
 */
async function safeParseAndPostEpoch(
    seq: number,
    text: string,
    fast: boolean = false,
    epochForThisRun: number
): Promise<void> {
    // Macrotask yield: allow the worker to process newer messages that may bump runEpoch
    await new Promise(r => setTimeout(r, 5));

    // If another clear/parse superseded this run, skip starting the heavy parse
    if (epochForThisRun !== runEpoch) {
        workerLogger.debug('stale parse skipped before start', seq, epochForThisRun, runEpoch);
        return;
    }

    try {
        const tree = parseMarkdown(seq, text, fast);
        post(<ResultMsg>{type: 'result', tree, seq: seq});
    } catch (e) {
        workerLogger.error('[md-worker]', e);
        const error = e instanceof Error ? e : new Error(String(e));
        post(<ErrorMsg>{type: 'error', message: error.message, stack: error.stack, seq: seq});
    }
}

function unhandledError(...args: unknown[]) {
    const message = args
        .map(arg => {
            if (typeof arg === 'string') {
                return arg;
            }
            if (arg instanceof Error) {
                return arg.stack || arg.message;
            }
            if (typeof arg === 'object' && arg !== null) {
                return JSON.stringify(arg);
            }
            return String(arg);
        })
        .join(' ');
    workerLogger.error(message);
}
