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

      // Search API
      setSearch: (query: string, caseSensitive: boolean) => void;
      clearSearch: () => void;
      nextMatch: () => void;
      prevMatch: () => void;
      scrollToCurrent: () => void;
      getSearchState: () => { total: number; current: number; query: string; caseSensitive: boolean; };
    };
    javaBridge?: {
      onAck: (epoch: number) => void;
      jsLog: (level: string, message: string) => void;
      searchStateChanged: (total: number, current: number) => void;
    };
  }
}

