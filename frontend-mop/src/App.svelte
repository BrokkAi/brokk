<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { fade } from 'svelte/transition';
  import type { Writable } from 'svelte/store';
  import type { BrokkEvent, Bubble } from './types';
  import MessageBubble from './components/MessageBubble.svelte';
  import { followWhenBottom } from './lib/followWhenBottom';

  export let eventStore: Writable<BrokkEvent>;
  export let spinnerStore: Writable<string>;

  let bubbles: Bubble[] = [];
  let nextId = 0;
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


  const spinnerUnsubscribe = spinnerStore.subscribe(message => {
    spinnerMessage = message;
  });

  // Unsubscribe when component is destroyed to prevent memory leaks
  onDestroy(() => {
    eventUnsubscribe();
    spinnerUnsubscribe();
  });
</script>

<style>
  .chat-container {
    display: flex;
    flex-direction: column;
    gap: 1em;
    max-width: 100%;
    padding: 0.5em;
    padding-right: 2em;
    position: absolute;
    top: 0.5em;
    bottom: 0.5em;
    right: 0.5em;
    left: 0.5em;
    overflow-y: auto;
    overflow-x: hidden;
  }
  #spinner {
    padding: 0.5em;
    color: #888;
    display: none;
    text-align: center;
  }
</style>

  <div
    class="chat-container"
    use:followWhenBottom={{ behavior: 'smooth' }}
  >
    {#each bubbles as bubble (bubble.id)}
      <div in:fade={{ duration: 200 }} out:fade={{ duration: 100 }}>
        <MessageBubble {bubble} />
      </div>
    {/each}
  </div>
<div id="spinner" style:display={spinnerMessage ? 'block' : 'none'}>{spinnerMessage}</div>
