import type { ResultMsg } from './shared';

type ResultHandler = (msg: ResultMsg) => void;

const handlers = new Map<number, ResultHandler>();

export function register(seq: number, handler: ResultHandler): void {
    handlers.set(seq, handler);
}

export function unregister(seq: number): void {
    handlers.delete(seq);
}

export function isRegistered(seq: number): boolean {
    return handlers.has(seq);
}

export function getRegisteredSeqs(): number[] {
    return Array.from(handlers.keys());
}

/**
 * Routes a worker result to the appropriate registered handler.
 * This is the single entry point for all worker results.
 * @param msg - The result message from the worker.
 */
export function onWorkerResult(msg: ResultMsg): void {
    const handler = handlers.get(msg.seq);
    if (handler) {
        handler(msg);
    } else {
        console.debug(`[parseRouter] No handler for seq ${msg.seq}`);
    }
}
