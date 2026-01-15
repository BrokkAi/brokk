import type {ResultMsg} from "./worker/shared";

export type BrokkEvent =
  | {
      type: 'chunk';
      text: string;
      msgType: 'USER' | 'AI' | 'SYSTEM';
      streaming: boolean;
      epoch: number;
      meta: {
        isNewMessage: boolean;
        isReasoning: boolean;
        isTerminal: boolean;
      };
    }
  | {
      type: 'clear';
      epoch: number;
    }
  | {
      type: 'history-reset';
      epoch: number;
    }
  | {
      type: 'history-task';
      epoch: number;
      taskSequence: number;
      compressed: boolean;
      summary?: string;
      messages?: { text: string; msgType: 'USER' | 'AI' | 'SYSTEM'; reasoning?: boolean }[];
    }
  | {
      type: 'live-summary';
      epoch: number;
      compressed: boolean;
      summary: string;
    }
  | {
      type: 'static-document';
      epoch: number;
      markdown: string | null;
    };

export type Bubble = {
  seq: number;
  type: 'USER' | 'AI' | 'SYSTEM';
  markdown: string;
  title?: string;
  iconId?: string;
};

export type SpinnerState = {
  visible: boolean;
  message: string;
};

export interface BufferItem {
  type: 'event' | 'call';
  seq: number;
  payload?: BrokkEvent;
  method?: string;
  args?: unknown[];
}

export type ReasoningState = {
  startTime?: number;           // ms timestamp when the reasoning started
  complete: boolean;            // true when the reasoning stream ends (was reasoningComplete)
  duration?: number;            // calculated duration in seconds
  isCollapsed: boolean;         // for UI state
};

export type TerminalState = {
  complete: boolean;            // true when terminal output is finalized
};

export type BubbleState = Bubble & {
  threadId: number;
  hast?: ResultMsg['tree'];     // latest parsed tree
  epoch?: number;               // mirrors Java event for ACK
  streaming: boolean;           // indicates if still growing

  // Optional terminal state - presence indicates this is a terminal output bubble
  terminalState?: TerminalState;

  // Optional reasoning state - presence indicates this is a reasoning bubble
  reasoningState?: ReasoningState;
};

/**
 * Represents a historical task/conversation entry.
 * When compressed=true, the backend may provide both a summary and full messages.
 * The frontend displays full messages by default and can toggle to the summary view.
 */
export type HistoryTask = {
  threadId: number;
  taskSequence: number;
  compressed: boolean;
  summary?: string;
  entries: BubbleState[];
};
