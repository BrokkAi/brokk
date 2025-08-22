<script lang="ts">
  import { onDestroy } from 'svelte';
  import type { Writable } from 'svelte/store';
  import type { BubbleState } from './stores/bubblesStore';
  import MessageBubble from './components/MessageBubble.svelte';
  import AIReasoningBubble from './components/AIReasoningBubble.svelte';
  import autoScroll, { escapeWhenUpPlugin } from '@yrobot/auto-scroll';
  import Spinner from './components/Spinner.svelte';

  export let bubblesStore: Writable<BubbleState[]>;

  let stopAutoScroll: (() => void) | null = null;

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
</style>

<div
  class="chat-container"
  id="chat-container"
>
  {#each $bubblesStore as bubble (bubble.seq)}
    {#if bubble.type === 'AI' && bubble.reasoning}
      <AIReasoningBubble {bubble} />
    {:else}
      <MessageBubble {bubble} />
    {/if}
  {/each}
  <Spinner />
</div>
