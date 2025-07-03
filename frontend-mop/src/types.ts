export interface ChunkEvent {
  type: 'chunk';
  text: string;
  isNew: boolean;
  streaming: boolean;
  msgType: 'USER' | 'AI' | 'SYSTEM';
  epoch: number;
}

export type BrokkEvent = ChunkEvent;

export interface Bubble {
  id: number;
  type: 'USER' | 'AI' | 'SYSTEM';
  markdown: string;
}
