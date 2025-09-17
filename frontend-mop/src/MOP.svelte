<script lang="ts">
  import { onDestroy } from 'svelte';
  import type { Writable } from 'svelte/store';
  import type { BubbleState } from './stores/bubblesStore';
  import CacheStatsDebug from './dev/components/CacheStatsDebug.svelte';
  import autoScroll, { escapeWhenUpPlugin } from '@yrobot/auto-scroll';
  import Spinner from './components/Spinner.svelte';
  import { zoomStore } from './stores/zoomStore';
  import { historyStore } from './stores/historyStore';
  import ThreadBlock from './components/ThreadBlock.svelte';
  import EmptyState from './components/EmptyState.svelte';
  import { get } from 'svelte/store';
  import { threadStore } from './stores/threadStore';

  export let bubblesStore: Writable<BubbleState[]>;

  let stopAutoScroll: (() => void) | null = null;

  const bubblesUnsubscribe = bubblesStore.subscribe(list => {
    const last: BubbleState | undefined = list.at(-1);
    const threadId = last?.threadId;
    const isCollapsed = threadId !== undefined ? get(threadStore)[threadId] ?? false : false;

    if (last?.streaming && !isCollapsed) {
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
    font-size: calc(14px * var(--zoom-level, 1));
    transition: font-size 0.2s ease;
  }

  .chat-container > :global(.thread-block) {
      margin-top: 0.8em;
  }
  .chat-container > :global(.thread-block:first-child) {
      margin-top: 0;
  }
  .history-live-separator {
    border-top: 1px solid var(--border-color-hex);
    margin: 1.5em 0 1em;
  }
</style>

<!-- Debug panels -->
<CacheStatsDebug />

<div
  class="chat-container"
  id="chat-container"
  style="--zoom-level: {$zoomStore}"
>
  {#if $historyStore.some(task => task.entries.length > 0) || $bubblesStore.length > 0}
    <!-- History tasks -->
    {#each $historyStore as task (task.threadId)}
      {#if task.entries.length > 0}
        <ThreadBlock threadId={task.threadId} bubbles={task.entries} />
      {/if}
    {/each}

    <!-- Separator line between history and live bubbles -->
    {#if $historyStore.some(task => task.entries.length > 0) && $bubblesStore.length > 0}
      <div class="history-live-separator"></div>
    {/if}

    <!-- Live bubbles -->
    {#if $bubblesStore.length > 0}
      <ThreadBlock threadId={$bubblesStore[0].threadId} bubbles={$bubblesStore} />
    {/if}
    <Spinner />
  {:else}
    <!-- Empty state when no history or live bubbles -->
    <EmptyState />
  {/if}
</div>