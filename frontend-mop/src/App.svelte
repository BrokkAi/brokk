<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import type { Writable } from 'svelte/store';
  import type {BrokkEvent, Bubble, SpinnerState} from './types';
  import MessageBubble from './components/MessageBubble.svelte';
  import autoScroll, { escapeWhenUpPlugin } from '@yrobot/auto-scroll';

  export let eventStore: Writable<BrokkEvent>;
  export let spinnerStore: Writable<SpinnerState>;

  let bubbles: Bubble[] = [];
  let nextId = 0;
  let spinner: SpinnerState = { visible: false, message: '' };
  let chatContainer: HTMLElement;
  let stopAutoScroll: (() => void) | null = null;

  onMount(() => {
    if (!chatContainer.id) {
      chatContainer.id = 'chat-container';
    }
  });

  // Subscribe to store changes explicitly to handle every event
  const eventUnsubscribe = eventStore.subscribe(event => {
    if (event.type === 'chunk') {
      if (event.streaming) {
        if (!stopAutoScroll) {
          stopAutoScroll = autoScroll({
            selector: '#chat-container',
            plugins: [escapeWhenUpPlugin({ threshold: 40 })],
            throttleTime: 100
          });
        }
      } else {
        if (stopAutoScroll) {
          stopAutoScroll();
          stopAutoScroll = null;
        }
      }

      if (!event.text && event.isNew) {
        // Direct reset without fade transition for JavaFX compatibility
        bubbles = [];
        nextId = 0;
        // Don't trigger fade - just continue showing the container
      } else if (event.text) {
        if (bubbles.length === 0 || event.isNew || event.msgType !== bubbles[bubbles.length - 1].type) {
          bubbles = [...bubbles, { id: nextId++, type: event.msgType!, markdown: event.text }];
        } else {
          bubbles[bubbles.length - 1].markdown += event.text;
          bubbles = [...bubbles]; // Trigger reactivity
        }
      }
    }
  });


  const spinnerUnsubscribe = spinnerStore.subscribe(state => {
    spinner = state;
  });

  // Unsubscribe when component is destroyed to prevent memory leaks
  onDestroy(() => {
    if (stopAutoScroll) {
      stopAutoScroll();
      stopAutoScroll = null;
    }
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
    padding-right: 1em;
    position: absolute;
    top: 0.5em;
    bottom: 0.5em;
    right: 0.5em;
    left: 0.5em;
    overflow-y: auto;
    overflow-x: hidden;
  }
  .spinner-msg {
    align-self: center;
    color: #888;
    padding: 0.5em 1em;
  }
</style>

  <div
    class="chat-container"
    bind:this={chatContainer}
  >
    {#each bubbles as bubble (bubble.id)}
      <div in:fade={{ duration: 200 }} out:fade={{ duration: 100 }}>
        <MessageBubble {bubble} />
      </div>
    {/each}
  {#if spinner.visible}
    <div id="spinner" class="spinner-msg" in:fade={{ duration: 150 }} out:fade={{ duration: 100 }}>
      {spinner.message}
    </div>
  {/if}
  </div>
