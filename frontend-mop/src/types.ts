export type BrokkEvent = {
  type: 'chunk' | 'clear' | 'spinner';
  text?: string;
  isNew?: boolean;
  streaming?: boolean;
  msgType?: 'USER' | 'AI' | 'SYSTEM';
  epoch: number;
  message?: string;
};

export type Bubble = {
  id: number;
  type: 'USER' | 'AI' | 'SYSTEM';
  markdown: string;
};

export type SpinnerState = {
  visible: boolean;
  message: string;
};
