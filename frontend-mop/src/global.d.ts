import type { BrokkEvent, BufferItem } from './types';
import type { SymbolLookupResult } from './stores/symbolCacheStore';

export {};

declare global {
  interface Window {
    brokk: {
      _buffer: BufferItem[];
      onEvent: (payload: BrokkEvent) => Promise<void>;
      getSelection: () => string;
      clear: () => void;
      setTheme: (dark: boolean, isDevMode?: boolean, zoom?: number) => void;
      showSpinner: (message?: string) => void;
      hideSpinner: () => void;

      // Search API
      setSearch: (query: string, caseSensitive: boolean) => void;
      clearSearch: () => void;
      nextMatch: () => void;
      prevMatch: () => void;
      scrollToCurrent: () => void;
      getSearchState: () => { total: number; current: number; query: string; caseSensitive: boolean; };

      // Symbol lookup API
      refreshSymbolLookup: (contextId?: string) => void;
      onSymbolLookupResponse?: (results: Record<string, SymbolLookupResult>, seq: number, contextId: string) => void;
    };
    javaBridge?: {
      onAck: (epoch: number) => void;
      jsLog: (level: string, message: string) => void;
      searchStateChanged: (total: number, current: number) => void;
      onSymbolClick: (symbolName: string, symbolExists: boolean, symbolFqn: string | null, x: number, y: number) => void;
      captureText:(text: string) => void;
      lookupSymbolsAsync?: (symbolNamesJson: string, seq: number | null, contextId: string) => void;
    };
  }
}
