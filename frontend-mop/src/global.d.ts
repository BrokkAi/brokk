import type { BrokkEvent, BufferItem } from './types';

export {};

declare global {
  interface Window {
    brokk: {
      _buffer: BufferItem[];
      onEvent: (payload: BrokkEvent) => Promise<void>;
      getSelection: () => string;
      clear: () => void;
      setTheme: (dark: boolean) => void;
      showSpinner: (message?: string) => void;
      hideSpinner: () => void;
    };
    javaBridge?: {
      onAck: (epoch: number) => void;
      jsLog: (level: string, message: string) => void;
    };
  }
}

