import './styles/global.scss';
import { mount } from 'svelte';
import { writable } from 'svelte/store';
import App from './App.svelte';
import type { BrokkEvent, SpinnerState } from './types';

// Declare global interfaces for Java bridge
declare global {
  interface Window {
    brokk: {
      _buffer: BufferItem[];
      onEvent: (payload: BrokkEvent) => void;
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

// Create a writable store for events
const eventStore = writable<BrokkEvent>({ type: 'chunk', text: '', isNew: false, streaming: false, msgType: 'SYSTEM', epoch: 0 });

// Create stores for UI commands
const spinnerStore = writable<SpinnerState>({ visible: false, message: '' });

// Instantiate the app using Svelte 5 API
const app = mount(App, {
  target: document.getElementById('mop-root')!,
  props: { eventStore, spinnerStore }
} as any); // Temporary workaround for TypeScript error, to be fixed with Svelte 5 typing updates

// Retrieve buffered calls and events from the early stub
const buffer = window.brokk._buffer || [];

// Replace the temporary brokk proxy with the real implementation
window.brokk = {
  _buffer: [],
  onEvent: (payload) => {
    console.log('Received event from Java bridge:', JSON.stringify(payload));
    eventStore.set(payload);

    // ACK after a frame render to ensure UI has updated
    if (payload.epoch) {
      requestAnimationFrame(() => {
        if (window.javaBridge) {
          window.javaBridge.onAck(payload.epoch);
        }
      });
    }
  },
  getSelection: () => {
    return window.getSelection()?.toString() ?? '';
  },
  clear: () => {
    eventStore.set({ type: 'chunk', text: '', isNew: true, streaming: false, msgType: 'SYSTEM', epoch: 0 });
  },
  setTheme: (dark) => {
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
    } else {
      console.log('Replaying call to', item.method, 'with args:', item.args);
      const brokk = window.brokk as Record<string, (...args: unknown[]) => unknown>;
      if (typeof brokk[item.method] === 'function') {
        brokk[item.method](...item.args);
      } else {
        console.warn('Method', item.method, 'no longer exists; skipping replay');
      }
    }
  });
}
