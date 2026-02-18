import { randomUUID } from "crypto";
import type {
  LiveResponse,
  ReadyResponse,
  CreateSessionResponse,
  SessionListResponse,
  SessionInfo,
  ActivityResponse,
  DiffResponse,
  ConversationResponse,
  JobSpecRequest,
  JobSubmitResponse,
  JobStatus,
  EventsResponse,
  ContextState,
  AddContextResponse,
  ApiErrorBody,
  ExecutionMode,
  TaskListData,
  ModelsResponse,
  CompletionsResponse,
} from "../types";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    public detail: string
  ) {
    super(`${code}: ${detail}`);
  }
}

export class BrokkClient {
  private baseUrl: string;
  private authToken: string;

  constructor(port: number, authToken: string) {
    this.baseUrl = `http://127.0.0.1:${port}`;
    this.authToken = authToken;
  }

  // ── Internal ───────────────────────────────────────

  private async request<T>(
    path: string,
    options: RequestInit = {},
    authenticated = true
  ): Promise<T> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...(options.headers as Record<string, string>),
    };

    if (authenticated) {
      headers["Authorization"] = `Bearer ${this.authToken}`;
    }

    const response = await fetch(`${this.baseUrl}${path}`, {
      ...options,
      headers,
    });

    if (!response.ok) {
      let body: ApiErrorBody;
      try {
        body = (await response.json()) as ApiErrorBody;
      } catch {
        throw new ApiError(response.status, "UNKNOWN", response.statusText);
      }
      throw new ApiError(response.status, body.code, body.message);
    }

    const text = await response.text();
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }

  // ── Health ─────────────────────────────────────────

  async checkLive(): Promise<LiveResponse> {
    return this.request<LiveResponse>("/health/live", {}, false);
  }

  async checkReady(): Promise<ReadyResponse> {
    return this.request<ReadyResponse>("/health/ready", {}, false);
  }

  // ── Sessions ───────────────────────────────────────

  async createSession(name: string): Promise<CreateSessionResponse> {
    return this.request<CreateSessionResponse>("/v1/sessions", {
      method: "POST",
      body: JSON.stringify({ name }),
    });
  }

  async listSessions(): Promise<SessionListResponse> {
    return this.request<SessionListResponse>("/v1/sessions");
  }

  async getCurrentSession(): Promise<SessionInfo> {
    return this.request<SessionInfo>("/v1/sessions/current");
  }

  async switchSession(sessionId: string): Promise<{ status: string; sessionId: string }> {
    return this.request("/v1/sessions/switch", {
      method: "POST",
      body: JSON.stringify({ sessionId }),
    });
  }

  // ── Activity ──────────────────────────────────────

  async getActivity(): Promise<ActivityResponse> {
    return this.request<ActivityResponse>("/v1/activity");
  }

  async undoToContext(contextId: string): Promise<{ status: string }> {
    return this.request("/v1/activity/undo", {
      method: "POST",
      body: JSON.stringify({ contextId }),
    });
  }

  async undoStep(): Promise<{ status: string }> {
    return this.request("/v1/activity/undo-step", { method: "POST" });
  }

  async redoStep(): Promise<{ status: string }> {
    return this.request("/v1/activity/redo", { method: "POST" });
  }

  async copyContext(contextId: string): Promise<{ status: string }> {
    return this.request("/v1/activity/copy-context", {
      method: "POST",
      body: JSON.stringify({ contextId }),
    });
  }

  async copyContextWithHistory(contextId: string): Promise<{ status: string }> {
    return this.request("/v1/activity/copy-context-history", {
      method: "POST",
      body: JSON.stringify({ contextId }),
    });
  }

  async newSessionFromContext(
    contextId: string,
    name?: string
  ): Promise<{ status: string }> {
    return this.request("/v1/activity/new-session", {
      method: "POST",
      body: JSON.stringify({ contextId, name }),
    });
  }

  async getContextDiff(contextId: string): Promise<DiffResponse> {
    return this.request<DiffResponse>(
      `/v1/activity/diff?contextId=${encodeURIComponent(contextId)}`
    );
  }

  async getConversation(): Promise<ConversationResponse> {
    return this.request<ConversationResponse>("/v1/context/conversation");
  }

  // ── Jobs ───────────────────────────────────────────

  async submitJob(
    taskInput: string,
    mode: ExecutionMode,
    plannerModel: string,
    opts: Partial<JobSpecRequest> = {}
  ): Promise<JobSubmitResponse> {
    const idempotencyKey = randomUUID();
    const body: JobSpecRequest = {
      taskInput,
      plannerModel,
      tags: { mode },
      ...opts,
    };

    return this.request<JobSubmitResponse>("/v1/jobs", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
    });
  }

  async getJobStatus(jobId: string): Promise<JobStatus> {
    return this.request<JobStatus>(`/v1/jobs/${jobId}`);
  }

  async getJobEvents(
    jobId: string,
    after = 0,
    limit = 200
  ): Promise<EventsResponse> {
    return this.request<EventsResponse>(
      `/v1/jobs/${jobId}/events?after=${after}&limit=${limit}`
    );
  }

  async cancelJob(jobId: string): Promise<void> {
    await this.request<void>(`/v1/jobs/${jobId}/cancel`, { method: "POST" });
  }

  // ── Context ────────────────────────────────────────

  async getContext(): Promise<ContextState> {
    return this.request<ContextState>("/v1/context?tokens=true");
  }

  async getTaskList(): Promise<TaskListData> {
    return this.request<TaskListData>("/v1/tasklist");
  }

  async dropFragments(fragmentIds: string[]): Promise<{ dropped: number }> {
    return this.request<{ dropped: number }>("/v1/context/drop", {
      method: "POST",
      body: JSON.stringify({ fragmentIds }),
    });
  }

  async pinFragment(
    fragmentId: string,
    pinned: boolean
  ): Promise<{ fragmentId: string; pinned: boolean }> {
    return this.request("/v1/context/pin", {
      method: "POST",
      body: JSON.stringify({ fragmentId, pinned }),
    });
  }

  async readonlyFragment(
    fragmentId: string,
    readonly: boolean
  ): Promise<{ fragmentId: string; readonly: boolean }> {
    return this.request("/v1/context/readonly", {
      method: "POST",
      body: JSON.stringify({ fragmentId, readonly }),
    });
  }

  async compressHistory(): Promise<void> {
    await this.request("/v1/context/compress-history", { method: "POST" });
  }

  async clearHistory(): Promise<void> {
    await this.request("/v1/context/clear-history", { method: "POST" });
  }

  async dropAll(): Promise<void> {
    await this.request("/v1/context/drop-all", { method: "POST" });
  }

  async addFiles(relativePaths: string[]): Promise<AddContextResponse> {
    return this.request<AddContextResponse>("/v1/context/files", {
      method: "POST",
      body: JSON.stringify({ relativePaths }),
    });
  }

  async addClasses(classNames: string[]): Promise<AddContextResponse> {
    return this.request<AddContextResponse>("/v1/context/classes", {
      method: "POST",
      body: JSON.stringify({ classNames }),
    });
  }

  async addMethods(methodNames: string[]): Promise<AddContextResponse> {
    return this.request<AddContextResponse>("/v1/context/methods", {
      method: "POST",
      body: JSON.stringify({ methodNames }),
    });
  }

  async addText(text: string): Promise<AddContextResponse> {
    return this.request<AddContextResponse>("/v1/context/text", {
      method: "POST",
      body: JSON.stringify({ text }),
    });
  }

  // ── Completions ────────────────────────────────────────

  async getCompletions(query: string, limit = 20): Promise<CompletionsResponse> {
    return this.request<CompletionsResponse>(
      `/v1/completions?query=${encodeURIComponent(query)}&limit=${limit}`
    );
  }

  // ── Models ──────────────────────────────────────────

  async getModels(): Promise<ModelsResponse> {
    return this.request<ModelsResponse>("/v1/models");
  }
}
