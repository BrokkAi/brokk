import type {ResultMsg} from "./worker/shared";

export type BrokkEvent = {
  type: 'chunk' | 'clear' | 'spinner';
  text?: string;
  isNew?: boolean;
  streaming?: boolean;
  msgType?: 'USER' | 'AI' | 'SYSTEM';
  reasoning?: boolean;
  epoch: number;
  message?: string;
};

export type Bubble = {
  id: number;
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

