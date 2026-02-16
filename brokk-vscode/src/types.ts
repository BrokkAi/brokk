// ── Health ──────────────────────────────────────────────

export interface LiveResponse {
  execId: string;
  version: string;
  protocolVersion: number;
}

export interface ReadyResponse {
  status: string;
  sessionId: string;
}

// ── Sessions ───────────────────────────────────────────

export interface CreateSessionResponse {
  sessionId: string;
  name: string;
}

// ── Jobs ───────────────────────────────────────────────

export type ExecutionMode =
  | "ARCHITECT"
  | "ASK"
  | "SEARCH"
  | "CODE"
  | "LUTZ"
  | "PLAN"
  | "ISSUE"
  | "REVIEW"
  | "ISSUE_WRITER";

export type JobState =
  | "QUEUED"
  | "RUNNING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export interface JobSpecRequest {
  sessionId?: string;
  taskInput: string;
  autoCommit?: boolean;
  autoCompress?: boolean;
  plannerModel: string;
  codeModel?: string;
  scanModel?: string;
  preScan?: boolean;
  contextText?: string[];
  tags: {
    mode: ExecutionMode;
    [key: string]: string | undefined;
  };
}

export interface JobSubmitResponse {
  jobId: string;
  state: JobState;
}

export interface JobStatus {
  jobId: string;
  state: JobState;
  startTime?: number;
  endTime?: number;
}

// ── Events ─────────────────────────────────────────────

export type EventType =
  | "NOTIFICATION"
  | "LLM_TOKEN"
  | "CONFIRM_REQUEST"
  | "STATE_HINT"
  | "CONTEXT_BASELINE"
  | "ERROR"
  | "COMMAND_RESULT";

export interface LlmTokenData {
  token: string;
  isReasoning: boolean;
  messageType: string;
  isNewMessage: boolean;
  isTerminal: boolean;
}

export interface NotificationData {
  level: string;
  message: string;
}

export interface ErrorData {
  message: string;
  title: string;
}

export interface ConfirmRequestData {
  message: string;
  title: string;
  optionType: number;
  messageType: number;
  defaultDecision: number;
}

export interface ContextBaselineData {
  count: number;
  snippet: string;
}

export interface CommandResultData {
  stage: string;
  command: string;
  attempt?: number;
  skipped: boolean;
  skipReason?: string;
  success: boolean;
  output: string;
  outputTruncated?: boolean;
  exception?: string;
}

export interface StateHintData {
  name: string;
  value: unknown;
  details?: string;
  count?: number;
}

export interface JobEvent {
  seq: number;
  timestamp: number;
  type: EventType;
  data: LlmTokenData | NotificationData | ErrorData | ConfirmRequestData | ContextBaselineData | CommandResultData | StateHintData | Record<string, unknown> | null;
}

export interface EventsResponse {
  events: JobEvent[];
  nextAfter: number;
}

// ── Context ────────────────────────────────────────────

export type ChipKind =
  | "EDIT"
  | "SUMMARY"
  | "HISTORY"
  | "TASK_LIST"
  | "INVALID"
  | "OTHER";

export interface ContextFragment {
  id: string;
  type: string;
  shortDescription: string;
  chipKind: ChipKind;
  pinned: boolean;
  readonly: boolean;
  valid: boolean;
  editable: boolean;
  tokens: number;
}

export interface ContextState {
  fragments: ContextFragment[];
  usedTokens: number;
  maxTokens: number;
}

export interface AddContextResponse {
  added: { id: string; relativePath?: string; className?: string; methodName?: string }[];
}

// ── Task List ─────────────────────────────────────────

export interface TaskItem {
  id: string;
  title: string;
  text: string;
  done: boolean;
}

export interface TaskListData {
  bigPicture: string | null;
  tasks: TaskItem[];
}

// ── Sessions (list / current) ──────────────────────────

export interface SessionInfo {
  id: string;
  name: string;
  created: number;
  modified: number;
}

export interface SessionListResponse {
  sessions: SessionInfo[];
  currentSessionId: string;
}

// ── Conversation ──────────────────────────────────────

export interface ConversationMessage {
  role: string;
  text: string;
  reasoning?: string;
}

export interface ConversationEntry {
  sequence: number;
  isCompressed: boolean;
  taskType?: string;
  messages?: ConversationMessage[];
  summary?: string;
}

export interface ConversationResponse {
  entries: ConversationEntry[];
}

// ── Activity ──────────────────────────────────────────

export interface DiffEntry {
  title: string;
  diff: string;
  linesAdded: number;
  linesDeleted: number;
}

export interface DiffResponse {
  diffs: DiffEntry[];
}

export interface ActivityEntry {
  contextId: string;
  action: string;
  taskType?: string;
  isAiResult: boolean;
}

export interface ActivityGroup {
  key: string;
  label: string;
  showHeader: boolean;
  isLastGroup: boolean;
  entries: ActivityEntry[];
}

export interface ActivityResponse {
  groups: ActivityGroup[];
  hasUndo: boolean;
  hasRedo: boolean;
}

// ── Models ────────────────────────────────────────────

export interface ModelInfo {
  name: string;
  location: string;
  supportsReasoningEffort: boolean;
  supportsReasoningDisable: boolean;
}

export interface ModelsResponse {
  models: (ModelInfo | string)[];
}

// ── Errors ─────────────────────────────────────────────

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: string | null;
}
