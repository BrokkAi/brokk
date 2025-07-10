import './styles/global.scss';
import { mount } from 'svelte';
import { tick } from 'svelte';
import App from './App.svelte';
import type { BrokkEvent } from './types';
import { bubblesStore, onBrokkEvent } from './bubblesStore';
import { spinnerStore, themeStore } from './stores';

// Declare global interfaces for Java bridge
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

// Define an explicit interface for buffer items
interface BufferItem {
  type: 'event' | 'call';
  seq: number;
  payload?: BrokkEvent;
  method?: string;
  args?: unknown[];
}

// Worker guard
if (!('Worker' in window)) {
  alert('This version of Brokk requires a newer runtime with Web Worker support.');
  throw new Error('Web Workers unsupported');
}

// Instantiate the app using Svelte 5 API
const app = mount(App, {
  target: document.getElementById('mop-root')!,
  props: { bubblesStore, spinnerStore }
} as any); // Temporary workaround for TypeScript error, to be fixed with Svelte 5 typing updates

// Retrieve buffered calls and events from the early stub
const buffer = window.brokk._buffer || [];

// Replace the temporary brokk proxy with the real implementation
window.brokk = {
  _buffer: [],
  onEvent: async (payload) => {
    onBrokkEvent(payload); // updates store & talks to worker

    // Wait until Svelte updated *and* browser painted
    await tick();
    requestAnimationFrame(() => {
      if (payload.epoch) window.javaBridge?.onAck(payload.epoch);
    });
  },
  getSelection: () => {
    return window.getSelection()?.toString() ?? '';
  },
  clear: () => {
    onBrokkEvent({ type: 'clear', epoch: 0 });
  },
  setTheme: (dark) => {
    themeStore.set(dark);
    const html = document.querySelector('html')!;
    const [addTheme, removeTheme] = dark ? ['theme-dark', 'theme-light'] : ['theme-light', 'theme-dark'];
    html.classList.add(addTheme);
    html.classList.remove(removeTheme);
  },
  showSpinner: (message = '') => {
    spinnerStore.set({ visible: true, message });
  },
  hideSpinner: () => {
    spinnerStore.set({ visible: false, message: '' });
  }
};

// Replay buffered calls and events in sequence order
if (buffer.length > 0) {
  console.log('Replaying', buffer.length, 'buffered items');
  buffer.sort((a, b) => a.seq - b.seq).forEach(item => {
    if (item.type === 'event' && item.payload) {
      console.log('Replaying event with epoch:', JSON.stringify(item.payload));
      window.brokk.onEvent(item.payload);
    } else if (item.type === 'call' && item.method) {
      console.log('Replaying call to', item.method, 'with args:', item.args);
      const brokk = window.brokk as Record<string, (...args: unknown[]) => unknown>;
      if (typeof brokk[item.method] === 'function') {
        brokk[item.method](...(item.args ?? []));
      } else {
        console.warn('Method', item.method, 'no longer exists; skipping replay');
      }
    }
  });
}
