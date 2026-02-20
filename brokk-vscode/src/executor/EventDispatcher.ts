import type { EventStreamManager } from "./EventStreamManager";
import type { EventType, JobEvent, StateHintData } from "../types";

type EventCallback = (data: unknown) => void;
type LogFn = (msg: string) => void;

interface Disposable {
  dispose: () => void;
}

/**
 * Lightweight pub/sub that routes events from EventStreamManager to subscribers.
 */
export class EventDispatcher {
  private typeListeners = new Map<EventType, EventCallback[]>();
  private stateHintListeners = new Map<string, EventCallback[]>();
  private subscription: Disposable;
  private log: LogFn;

  constructor(streamManager: EventStreamManager, log?: LogFn) {
    this.log = log ?? (() => {});
    this.subscription = streamManager.onEvent((event) =>
      this.dispatch(event)
    );
  }

  /** Subscribe to all events of a specific type. */
  on(type: EventType, callback: EventCallback): Disposable {
    let list = this.typeListeners.get(type);
    if (!list) {
      list = [];
      this.typeListeners.set(type, list);
    }
    list.push(callback);
    return {
      dispose: () => {
        const arr = this.typeListeners.get(type);
        if (arr) {
          const idx = arr.indexOf(callback);
          if (idx >= 0) arr.splice(idx, 1);
        }
      },
    };
  }

  /** Subscribe to STATE_HINT events with a specific `name` value. */
  onStateHint(name: string, callback: EventCallback): Disposable {
    let list = this.stateHintListeners.get(name);
    if (!list) {
      list = [];
      this.stateHintListeners.set(name, list);
    }
    list.push(callback);
    return {
      dispose: () => {
        const arr = this.stateHintListeners.get(name);
        if (arr) {
          const idx = arr.indexOf(callback);
          if (idx >= 0) arr.splice(idx, 1);
        }
      },
    };
  }

  dispose(): void {
    this.subscription.dispose();
    this.typeListeners.clear();
    this.stateHintListeners.clear();
  }

  private dispatch(event: JobEvent): void {
    // Notify type-level listeners
    const typeListeners = this.typeListeners.get(event.type);
    if (typeListeners) {
      for (const cb of typeListeners) {
        try {
          cb(event.data);
        } catch {
          // Don't let a bad listener kill dispatch
        }
      }
    }

    // For STATE_HINT, also notify name-specific listeners
    if (event.type === "STATE_HINT" && event.data) {
      const hint = event.data as StateHintData;
      if (hint.name) {
        const hintListeners = this.stateHintListeners.get(hint.name);
        this.log(`[EventDispatcher] STATE_HINT name=${hint.name}, listeners=${hintListeners?.length ?? 0}`);
        if (hintListeners) {
          for (const cb of hintListeners) {
            try {
              cb(hint);
            } catch {
              // Don't let a bad listener kill dispatch
            }
          }
        }
      }
    }
  }
}
