export type Session = {
  sessionId: string;
  name: string;
};

export type JobEvent = {
  seq: number;
  type: "info" | "warn" | "error" | "user" | "assistant" | string;
  message: string;
  timestamp?: string;
};
