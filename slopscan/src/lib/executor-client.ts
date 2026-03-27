/**
 * HTTP client for communicating with the Brokk Headless Executor.
 *
 * See docs/headless-executor.md for full API documentation.
 * See brokk-code/brokk_code/executor.py for reference implementation.
 */

export interface ExecutorConfig {
  baseUrl: string;
  authToken: string;
}

export interface JobSubmission {
  taskInput: string;
  plannerModel: string;
  scanModel?: string;
  codeModel?: string;
  tags?: Record<string, string>;
  autoCompress?: boolean;
  preScan?: boolean;
}

export interface JobStatus {
  jobId: string;
  state: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  startTime?: number;
  endTime?: number;
  progressPercent?: number;
  error?: string;
}

export interface JobEvent {
  seq: number;
  timestamp: number;
  type: string;
  data: unknown;
}

export interface EventsResponse {
  events: JobEvent[];
  nextAfter: number;
}

export class ExecutorClient {
  private baseUrl: string;
  private authToken: string;

  constructor(config: ExecutorConfig) {
    this.baseUrl = config.baseUrl.replace(/\/$/, "");
    this.authToken = config.authToken;
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    headers?: Record<string, string>
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const init: RequestInit = {
      method,
      headers: {
        Authorization: `Bearer ${this.authToken}`,
        "Content-Type": "application/json",
        ...headers,
      },
    };
    if (body !== undefined) {
      init.body = JSON.stringify(body);
    }
    const response = await fetch(url, init);

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`HTTP ${response.status}: ${errorText}`);
    }

    return response.json() as Promise<T>;
  }

  /** Check if executor is live (no auth required) */
  async healthCheck(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/health/live`);
      return response.ok;
    } catch {
      return false;
    }
  }

  /** Submit a new job */
  async submitJob(job: JobSubmission): Promise<{ jobId: string; sessionId?: string }> {
    const idempotencyKey = crypto.randomUUID();
    return this.request("POST", "/v1/jobs", job, {
      "Idempotency-Key": idempotencyKey,
    });
  }

  /** Get job status */
  async getJobStatus(jobId: string): Promise<JobStatus> {
    return this.request("GET", `/v1/jobs/${jobId}`);
  }

  /** Get job events (polling) */
  async getJobEvents(jobId: string, afterSeq = -1, limit = 100): Promise<EventsResponse> {
    return this.request("GET", `/v1/jobs/${jobId}/events?after=${afterSeq}&limit=${limit}`);
  }

  /** Cancel a running job */
  async cancelJob(jobId: string): Promise<void> {
    await this.request("POST", `/v1/jobs/${jobId}/cancel`);
  }

  /** Start GitHub OAuth device flow */
  async startGitHubOAuth(): Promise<{
    verificationUri: string;
    userCode: string;
    expiresIn: number;
    interval: number;
  }> {
    return this.request("POST", "/v1/github/oauth/start");
  }

  /** Check GitHub OAuth status */
  async getGitHubOAuthStatus(): Promise<{
    state: string;
    connected: boolean;
    username?: string;
  }> {
    return this.request("GET", "/v1/github/oauth/status");
  }

  /** Generator for streaming job events with adaptive polling */
  async *streamEvents(jobId: string): AsyncGenerator<JobEvent, void, unknown> {
    const terminalStates = new Set(["COMPLETED", "FAILED", "CANCELLED"]);
    let afterSeq = -1;
    let currentSleep = 50;
    const maxSleep = 500;

    while (true) {
      const { events, nextAfter } = await this.getJobEvents(jobId, afterSeq);
      afterSeq = nextAfter;

      for (const event of events) {
        yield event;
      }

      // Check job status
      const status = await this.getJobStatus(jobId);
      if (terminalStates.has(status.state)) {
        break;
      }

      // Adaptive backoff
      if (events.length > 0) {
        currentSleep = 50;
        if (events.length < 100) {
          await this.sleep(50);
        }
      } else {
        await this.sleep(currentSleep);
        currentSleep = Math.min(maxSleep, currentSleep * 2);
      }
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}

/** Create a client from environment variables */
export function createExecutorClient(): ExecutorClient {
  const baseUrl = import.meta.env.VITE_EXECUTOR_URL as string || "http://localhost:8080";
  const authToken = import.meta.env.VITE_AUTH_TOKEN as string || "";

  return new ExecutorClient({ baseUrl, authToken });
}
