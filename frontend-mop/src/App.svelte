<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { fade } from 'svelte/transition';
  import type { Writable } from 'svelte/store';
  import type { BrokkEvent, Bubble } from './types';
  import MessageBubble from './components/MessageBubble.svelte';

  export let eventStore: Writable<BrokkEvent>;
  export let themeStore: Writable<boolean>;
  export let spinnerStore: Writable<string>;

  let bubbles: Bubble[] = [];
  let nextId = 0;
  let isDarkTheme = false;
  let spinnerMessage = '';

  // Subscribe to store changes explicitly to handle every event
  const eventUnsubscribe = eventStore.subscribe(event => {
    if (event.type === 'chunk') {
      if (!event.text && event.isNew) {
        // Direct reset without fade transition for JavaFX compatibility
        bubbles = [];
        nextId = 0;
        // Don't trigger fade - just continue showing the container
      } else if (event.text) {
        if (bubbles.length === 0 || event.isNew || event.msgType !== bubbles[bubbles.length - 1].type) {
          bubbles = [...bubbles, { id: nextId++, type: event.msgType, markdown: event.text }];
        } else {
          bubbles[bubbles.length - 1].markdown += event.text;
          bubbles = [...bubbles]; // Trigger reactivity
        }
      }
    }
  });

  const themeUnsubscribe = themeStore.subscribe(dark => {
    isDarkTheme = dark;
  });

  const spinnerUnsubscribe = spinnerStore.subscribe(message => {
    spinnerMessage = message;
  });

  // Unsubscribe when component is destroyed to prevent memory leaks
  onDestroy(() => {
    eventUnsubscribe();
    themeUnsubscribe();
    spinnerUnsubscribe();
  });
</script>

<style>
  :global(body.theme-dark) {
    background-color: #2b2b2b;
    color: #bbb;
  }

  .chat-container {
    display: flex;
    flex-direction: column;
    gap: 1em;
    max-width: 100%;
    margin: 0 auto;
  }
  #spinner {
    padding: 0.5em;
    color: #888;
    display: none;
    text-align: center;
  }
  body.theme-dark #spinner {
    color: #888;
  }
</style>

  <div
    class="chat-container"
    class:theme-dark={isDarkTheme}
  >
    {#each bubbles as bubble (bubble.id)}
      <div in:fade={{ duration: 200 }} out:fade={{ duration: 200 }}>
        <MessageBubble {bubble} dark={isDarkTheme} />
      </div>
    {/each}
  </div>
<div id="spinner" style:display={spinnerMessage ? 'block' : 'none'}>{spinnerMessage}</div>
