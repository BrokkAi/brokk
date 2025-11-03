<script lang="ts">
  import Icon from "@iconify/svelte";
  import HastRenderer from './HastRenderer.svelte';
  import { rendererPlugins } from '../lib/renderer-plugins';
  import type { BubbleState } from '../stores/bubblesStore';
  import { toggleBubbleCollapsed } from '../stores/bubblesStore';

  export let bubble: BubbleState;

  const hlVar = '--message-border-ai-reasoning';
  const bgVar = '--message-background';

  // Round to 1 decimal as the UI displays
  $: displayDuration = bubble.duration != null ? Number(bubble.duration.toFixed(1)) : 0;

  // Show "Thoughts" when the rounded display is 0.0
  $: showThoughtsLabel = bubble.reasoningComplete && displayDuration === 0;

  function toggleCollapse() {
    // Only allow toggling when reasoning is complete.
    if (bubble.reasoningComplete) {
      toggleBubbleCollapsed(bubble.seq);
    }
  }
</script>

<div class="message-wrapper">
  <header class="header reasoning-header {bubble.isCollapsed ? 'collapsed' : ''}" style="color: var({hlVar});" on:click={toggleCollapse}>
    {#if bubble.reasoningComplete}
      <Icon icon={bubble.isCollapsed ? 'mdi:chevron-right' : 'mdi:chevron-down'} style="color: var(--ai-reasoning-header-foreground); margin-right: 0.35em;" />
      <span class="title" style="color: var(--ai-reasoning-header-foreground);">
        {#if showThoughtsLabel}
          Thoughts
        {:else}
          Thought for {displayDuration} seconds
        {/if}
      </span>
    {:else}
      <Icon icon="mdi:loading" class="spin-icon" style="color: var({hlVar}); margin-right: 0.35em;" />
      <span class="title">Reasoning progress...</span>
    {/if}
  </header>
  {#if !bubble.isCollapsed}
    <div
      class="message-bubble"
      style="
        background-color: var({bgVar});
        border-left: 4px solid var({hlVar});
        color: var(--chat-text);
      "
    >
      {#if bubble.hast}
        <HastRenderer tree={bubble.hast} plugins={rendererPlugins} />
      {/if}
    </div>
  {/if}
</div>

<style>
  .message-wrapper {
    display: flex;
    flex-direction: column;
    gap: 0.3em;
    width: 100%;
  }

  .message-bubble {
    border-radius: 0.9em;
    padding: 0.8em 1.1em;
    display: flex;
    flex-direction: column;
    gap: 0.4em;
    word-break: break-word;
  }

  /* High contrast mode: add a subtle dotted border around the entire bubble */
  :global(.theme-high-contrast) .message-bubble {
    border: 1px dotted rgba(230, 230, 230, 0.3);
    border-left: 4px solid var(--border-color-hex); /* Preserve the accent border */
  }

  .header {
    display: flex;
    align-items: center;
    font-weight: 600;
    font-size: 0.95em;
  }

  .reasoning-header {
    cursor: pointer;
    user-select: none; /* Prevents text selection on click */
  }

  /* High contrast mode: add border to collapsed bubble header */
  :global(.theme-high-contrast) .reasoning-header.collapsed {
    border: 1px dotted rgba(230, 230, 230, 0.3);
    border-left: 4px solid var(--message-border-ai-reasoning);
    padding: 0.5em 0.8em;
    border-radius: 0.5em;
  }

  :global(.spin-icon) {
    animation: spin 1.5s linear infinite;
  }

  @keyframes spin {
    from {
      transform: rotate(0deg);
    }
    to {
      transform: rotate(360deg);
    }
  }
</style>
