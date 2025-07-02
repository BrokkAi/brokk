import { mount } from 'svelte';
import { writable } from 'svelte/store';
import App from './App.svelte';
import type { BrokkEvent } from './types';

// Declare global interfaces for Java bridge
declare global {
  interface Window {
    brokk: {
      onEvent: (payload: BrokkEvent) => void;
      _eventBuffer: BrokkEvent[];
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

// Save any buffered events
const bufferedEvents = window.brokk._eventBuffer || [];

// Replace the temporary brokk event handler with the real one
window.brokk = {
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

// Process any events that were buffered before initialization
if (bufferedEvents.length > 0) {
  bufferedEvents.forEach(event => {
    window.brokk.onEvent(event);
  });
}
