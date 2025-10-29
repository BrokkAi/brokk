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
  import { historyCollapsedStore } from './stores/historyCollapsedStore';
  import Icon from '@iconify/svelte';

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

  // Derived UI helpers for rendering
  $: hasHistory = $historyStore.some(t => t.entries.length > 0);
  $: hasLive = $bubblesStore.length > 0;
  $: historyTaskCount = $historyStore.filter(t => t.entries.length > 0).length;

  // Toggle handlers for collapse control
  function toggleHistoryCollapsed() {
    historyCollapsedStore.update(v => !v);
  }

  function onToggleKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      toggleHistoryCollapsed();
    }
  }

  function onSummaryKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      historyCollapsedStore.set(false);
    }
  }
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

  /* Separator container with centered toggle */
  .history-live-separator-container {
    display: flex;
    align-items: center;
    gap: 0.8em;
    margin: 1.5em 0 1em;
  }
  .history-live-separator-container .line {
    flex: 1;
    height: 1px;
    background: var(--border-color-hex);
  }
  .history-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 2em;
    height: 2em;
    border-radius: 999px;
    background: var(--message-background);
    color: var(--chat-text);
    border: 1px solid var(--border-color-hex);
    cursor: pointer;
    user-select: none;
    transition: background-color 0.15s ease, color 0.15s ease, border-color 0.15s ease;
  }
  .history-toggle:hover {
    background: color-mix(in srgb, var(--chat-background) 50%, var(--message-background));
  }
  .history-toggle:focus {
    outline: 2px solid var(--border-color-hex);
    outline-offset: 2px;
  }
  .history-toggle:focus-visible {
    outline: 2px solid var(--border-color-hex);
    outline-offset: 2px;
  }

  /* Collapsed Header Preview */
  .header-preview {
    display: grid;
    grid-template-columns: auto auto 1fr auto auto auto;
    align-items: center;
    gap: 0.8em;
    cursor: pointer;
    user-select: none;
    background-color: var(--message-background);
    border-left: 4px solid var(--border-color-hex);
    color: var(--chat-text);
    padding: 0.6em 1.1em;
    line-height: 1.5;
    border-radius: 0.9em;
    transition: background-color 0.15s ease;
  }
  .header-preview:focus {
    outline: 2px solid var(--border-color-hex);
    outline-offset: 2px;
  }
  .header-preview:hover {
    background: color-mix(in srgb, var(--chat-background) 50%, var(--message-background));
  }
  :global(.theme-high-contrast) .header-preview {
    border: 1px dotted rgba(230, 230, 230, 0.3);
    border-left: 4px solid var(--border-color-hex);
  }
  .tag { font-weight: 600; }
  .content-preview {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    height: 1.5em; /* line-height */
    min-width: 0; /* allow ellipsis inside grid */
  }
  .content-preview :global(p),
  .content-preview :global(h1),
  .content-preview :global(h2),
  .content-preview :global(h3),
  .content-preview :global(ul),
  .content-preview :global(ol),
  .content-preview :global(pre),
  .content-preview :global(blockquote),
  .content-preview :global(li) {
    display: inline;
    font-size: 1em;
    border: none;
    padding: 0;
    margin: 0;
    font-weight: normal;
  }
</style>

<!-- Debug panels -->
<CacheStatsDebug />

<div
  class="chat-container"
  id="chat-container"
  style="--zoom-level: {$zoomStore}"
>
  {#if hasHistory || hasLive}
    <!-- History tasks (expanded) OR a single-line summary (collapsed) -->
    {#if hasHistory}
      {#if !$historyCollapsedStore}
        {#each $historyStore as task (task.threadId)}
          {#if task.entries.length > 0}
            <ThreadBlock taskSequence={task.taskSequence} threadId={task.threadId} bubbles={task.entries} />
          {/if}
        {/each}
      {:else}
        <header
          class="header-preview"
          role="button"
          tabindex="0"
          aria-label="Expand history"
          on:click={() => historyCollapsedStore.set(false)}
          on:keydown={onSummaryKeydown}
        >
          <Icon icon="mdi:chevron-right" style="color: var(--chat-text);" />
          <span class="tag">History:</span>
          <div class="content-preview"> {historyTaskCount} entries</div>
        </header>
      {/if}
    {/if}

    <!-- Separator line with centered toggle between history and live bubbles -->
    {#if hasHistory && hasLive}
      <div class="history-live-separator-container">
        <div class="line"></div>
        <div
          class="history-toggle"
          role="button"
          tabindex="0"
          aria-label={$historyCollapsedStore ? 'Expand history' : 'Collapse history'}
          aria-pressed={!$historyCollapsedStore}
          on:click={toggleHistoryCollapsed}
          on:keydown={onToggleKeydown}
        >
          <Icon icon={$historyCollapsedStore ? 'mdi:chevron-right' : 'mdi:chevron-down'} style="color: var(--chat-text);" />
        </div>
        <div class="line"></div>
      </div>
    {/if}

    <!-- Live bubbles -->
    {#if hasLive}
      <ThreadBlock threadId={$bubblesStore[0].threadId} bubbles={$bubblesStore} />
    {/if}
    <Spinner />
  {:else}
    <!-- Empty state when no history or live bubbles -->
    <EmptyState />
  {/if}
</div>
