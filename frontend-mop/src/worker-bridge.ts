import { wrap } from 'comlink';
import type { MarkdownWorker } from './markdown.worker';

export let mdWorker: ReturnType<typeof wrap<MarkdownWorker>> | undefined;

let workerSupported = false;
try {
  workerSupported = !!window.Worker;
} catch (e) {
  console.error('[Worker test] Worker not supported:', e);
  workerSupported = false;
}

export const canUseWorker = workerSupported;

if (canUseWorker) {
  try {
    const worker = new Worker('/markdown.worker.mjs', { type: 'module' });
    worker.onerror = (e: ErrorEvent) => {
      console.error('[Worker error event]', {
        message: e.message || 'Unknown error',
        filename: e.filename,
        lineno: e.lineno,
        colno: e.colno,
        error: e.error
      });
    };
    worker.onmessageerror = (e) => console.error('[Worker message error]', e);
    worker.onmessage = (e) => console.log('[Worker first message]', e.data);
    mdWorker = wrap<MarkdownWorker>(worker);
  } catch (e) {
    console.error('[Worker instantiation failed]', e);
    // Optionally, set canUseWorker to false to trigger fallback
  }
}
