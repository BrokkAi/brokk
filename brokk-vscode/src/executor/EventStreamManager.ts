import type { BrokkClient } from "./client";
import type { JobEvent, JobState } from "../types";

type LogFn = (msg: string) => void;

const MIN_SLEEP_MS = 100;
const MAX_SLEEP_MS = 1000;
const BACKOFF_FACTOR = 1.5;
const STATUS_CHECK_INTERVAL_MS = 3000;

type EventCallback = (event: JobEvent) => void;
type JobFinishedCallback = (state: JobState) => void;

/**
 * Adaptive polling for job events, matching the Python TUI pattern.
 *
 * - Polls GET /v1/jobs/{id}/events with adaptive sleep (50ms–500ms)
 * - Exponential backoff when idle, resets when events arrive
 * - Checks job status every 2s
 * - Auto-stops when job reaches terminal state
 */
export class EventStreamManager {
  private client: BrokkClient;
  private currentJobId: string | null = null;
  private lastSeq = 0;
  private running = false;
  private sleepMs = MIN_SLEEP_MS;
  private eventCallbacks: EventCallback[] = [];
  private finishedCallbacks: JobFinishedCallback[] = [];
  private pollTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private statusIntervalId: ReturnType<typeof setInterval> | null = null;
  private log: LogFn;

  constructor(client: BrokkClient, log?: LogFn) {
    this.client = client;
    this.log = log ?? (() => {});
  }

  onEvent(callback: EventCallback): { dispose: () => void } {
    this.eventCallbacks.push(callback);
    return {
      dispose: () => {
        const idx = this.eventCallbacks.indexOf(callback);
        if (idx >= 0) this.eventCallbacks.splice(idx, 1);
      },
    };
  }

  onJobFinished(callback: JobFinishedCallback): { dispose: () => void } {
    this.finishedCallbacks.push(callback);
    return {
      dispose: () => {
        const idx = this.finishedCallbacks.indexOf(callback);
        if (idx >= 0) this.finishedCallbacks.splice(idx, 1);
      },
    };
  }

  startStreaming(jobId: string): void {
    this.stopStreaming();
    this.currentJobId = jobId;
    this.lastSeq = 0;
    this.sleepMs = MIN_SLEEP_MS;
    this.running = true;

    this.log(`[EventStream] startStreaming jobId=${jobId}`);
    this.schedulePoll();
    this.statusIntervalId = setInterval(
      () => this.checkJobStatus(),
      STATUS_CHECK_INTERVAL_MS
    );
  }

  stopStreaming(): void {
    this.running = false;
    this.currentJobId = null;
    if (this.pollTimeoutId !== null) {
      clearTimeout(this.pollTimeoutId);
      this.pollTimeoutId = null;
    }
    if (this.statusIntervalId !== null) {
      clearInterval(this.statusIntervalId);
      this.statusIntervalId = null;
    }
  }

  get isStreaming(): boolean {
    return this.running;
  }

  get activeJobId(): string | null {
    return this.currentJobId;
  }

  private schedulePoll(): void {
    if (!this.running) return;
    this.pollTimeoutId = setTimeout(() => this.pollEvents(), this.sleepMs);
  }

  private async pollEvents(): Promise<void> {
    if (!this.running || !this.currentJobId) return;

    try {
      const response = await this.client.getJobEvents(
        this.currentJobId,
        this.lastSeq
      );

      if (response.events.length > 0) {
        // Events arrived — reset to fast polling
        this.sleepMs = MIN_SLEEP_MS;
        const types = response.events.map(e => e.type);
        this.log(`[EventStream] ${response.events.length} events: ${types.join(", ")}`);
        for (const event of response.events) {
          for (const cb of this.eventCallbacks) {
            try {
              cb(event);
            } catch (err) {
              this.log(`[EventStream] callback error: ${err instanceof Error ? err.message : String(err)}`);
            }
          }
        }
      } else {
        // No events — exponential backoff
        this.sleepMs = Math.min(
          this.sleepMs * BACKOFF_FACTOR,
          MAX_SLEEP_MS
        );
      }

      this.lastSeq = response.nextAfter;
    } catch (err) {
      this.log(`[EventStream] poll error: ${err instanceof Error ? err.message : String(err)}`);
      this.sleepMs = Math.min(this.sleepMs * BACKOFF_FACTOR, MAX_SLEEP_MS);
    }

    this.schedulePoll();
  }

  private async checkJobStatus(): Promise<void> {
    if (!this.running || !this.currentJobId) return;

    try {
      const status = await this.client.getJobStatus(this.currentJobId);
      if (
        status.state === "COMPLETED" ||
        status.state === "FAILED" ||
        status.state === "CANCELLED"
      ) {
        // Do one final poll to grab any remaining events
        await this.pollEvents();
        this.stopStreaming();
        for (const cb of this.finishedCallbacks) {
          try {
            cb(status.state);
          } catch (err) {
            this.log(`[EventStream] finished callback error: ${err instanceof Error ? err.message : String(err)}`);
          }
        }
      }
    } catch (err) {
      this.log(`[EventStream] status check error: ${err instanceof Error ? err.message : String(err)}`);
    }
  }
}
