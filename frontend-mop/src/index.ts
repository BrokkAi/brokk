import { mount } from 'svelte';
import { writable } from 'svelte/store';
import App from './App.svelte';
import type { BrokkEvent } from './types';

// Declare global interfaces for Java bridge
declare global {
  interface Window {
    brokk: {
      _eventBuffer: BrokkEvent[];
      _callQueue: { method: string; args: unknown[] }[];
      onEvent: (payload: BrokkEvent) => void;
      getSelection: () => string;
      clear: () => void;
      setTheme: (dark: boolean) => void;
      showSpinner: (message: string) => void;
    };
    javaBridge?: {
      onAck: (epoch: number) => void;
      jsLog: (level: string, message: string) => void;
    };
  }
}

// Create a writable store for events
const eventStore = writable<BrokkEvent>({ type: 'chunk', text: '', isNew: false, msgType: 'SYSTEM', epoch: 0 });

// Create stores for UI commands
const themeStore = writable<boolean>(false);
const spinnerStore = writable<string>('');

// Instantiate the app using Svelte 5 API
const app = mount(App, {
  target: document.body,
  props: { eventStore, themeStore, spinnerStore }
});

// Retrieve buffered calls and events from the early stub
const pendingCalls = window.brokk._callQueue || [];
const bufferedEvents = window.brokk._eventBuffer || [];

// Replace the temporary brokk proxy with the real implementation
window.brokk = {
  _callQueue: [],
  _eventBuffer: [],
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
    eventStore.set({ type: 'chunk', text: '', isNew: true, msgType: 'SYSTEM', epoch: 0 });
  },
  setTheme: (dark) => {
    themeStore.set(dark);
    if (dark) {
      document.body.classList.add('theme-dark');
    } else {
      document.body.classList.remove('theme-dark');
    }
  },
  showSpinner: (message) => {
    spinnerStore.set(message);
  }
};

// Process any buffered events first to maintain temporal order
if (bufferedEvents.length > 0) {
  console.log('Replaying', bufferedEvents.length, 'buffered events');
  bufferedEvents.forEach(event => {
    window.brokk.onEvent(event);
  });
}

// Then replay any other buffered method calls
if (pendingCalls.length > 0) {
  console.log('Replaying', pendingCalls.length, 'buffered method calls');
  pendingCalls.forEach(({ method, args }) => {
    console.log('Replaying call to', method, 'with args:', args);
    const brokk = window.brokk as Record<string, (...args: unknown[]) => unknown>;
    if (typeof brokk[method] === 'function') {
      brokk[method](...args);
    } else {
      console.warn('Method', method, 'no longer exists; skipping replay');
    }
  });
}
