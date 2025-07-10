<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { fade } from 'svelte/transition';
  import type { Writable } from 'svelte/store';
  import type { SpinnerState } from './types';
  import type { BubbleState } from './stores/bubblesStore';
  import MessageBubble from './components/MessageBubble.svelte';
  import autoScroll, { escapeWhenUpPlugin } from '@yrobot/auto-scroll';
  import { themeStore } from './stores/themeStore';

  export let bubblesStore: Writable<BubbleState[]>;
  export let spinnerStore: Writable<SpinnerState>;

  let spinner: SpinnerState = { visible: false, message: '' };
  let stopAutoScroll: (() => void) | null = null;

  onMount(() => {
    // Check initial theme
    const isDark = document.querySelector('html')?.classList.contains('theme-dark') || false;
    themeStore.set(isDark);
  });

  const spinnerUnsubscribe = spinnerStore.subscribe(state => {
    spinner = state;
  });

  const bubblesUnsubscribe = bubblesStore.subscribe(list => {
    const last: BubbleState | undefined = list.at(-1);
    if (last?.streaming) {
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
  });

  // Unsubscribe when component is destroyed to prevent memory leaks
  onDestroy(() => {
    if (stopAutoScroll) {
      stopAutoScroll();
      stopAutoScroll = null;
    }
    bubblesUnsubscribe();
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
  id="chat-container"
>
  {#each $bubblesStore as bubble (bubble.id)}
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
