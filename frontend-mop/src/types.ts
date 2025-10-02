import type {ResultMsg} from "./worker/shared";

export type BrokkEvent =
  | {
      type: 'chunk';
      text: string;
      isNew: boolean;
      streaming: boolean;
      msgType: 'USER' | 'AI' | 'SYSTEM';
      reasoning: boolean;
      epoch: number;
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
      messages?: { text: string; msgType: 'USER' | 'AI' | 'SYSTEM', reasoning: boolean }[];
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

export type BubbleState = Bubble & {
  threadId: number;
  hast?: ResultMsg['tree'];     // latest parsed tree
  epoch?: number;               // mirrors Java event for ACK
  streaming: boolean;           // indicates if still growing

  // Properties for Reasoning bubbles
  reasoning?: boolean;
  startTime?: number;           // ms timestamp when the reasoning started
  reasoningComplete?: boolean;  // true when the reasoning stream ends
  duration?: number;            // calculated duration in seconds
  isCollapsed?: boolean;        // for UI state
};

export type HistoryTask = {
  threadId: number;
  taskSequence: number;
  compressed: boolean;
  entries: BubbleState[];
};
