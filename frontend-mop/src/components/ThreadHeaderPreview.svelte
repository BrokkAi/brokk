<script lang="ts">
  import Icon from '@iconify/svelte';
  import HastRenderer from './HastRenderer.svelte';
  import { rendererPlugins } from '../lib/renderer-plugins';
  import ThreadMeta from './ThreadMeta.svelte';

  // State/props
  export let collapsed: boolean;
  export let compressed: boolean;
  export let tag: string;
  export let previewTree: any | undefined;

  // Metrics/meta
  export let adds: number;
  export let dels: number;
  export let showEdits: boolean;
  export let msgLabel: string;
  export let totalLines: number;

  // Identity/controls
  export let threadId: number;
  export let taskSequence: number | undefined;
  export let allowDelete: boolean;
  export let showSummary: boolean;

  // Handlers
  export let onToggleCollapse: () => void;
  export let onSetViewMode: ((mode: 'messages' | 'summary') => void) | undefined;
  export let onCopy: () => void;
  export let onDelete: (threadId: number) => void;
  export let hasMessages: boolean = true;

  function handleToggle() {
    onToggleCollapse?.();
  }
</script>

<header
  class="header-preview"
  style={`border-left-color: ${compressed || showSummary ? 'var(--summary-border-color, #9b59b6)' : 'var(--border-color-hex)'};`}
  on:click={handleToggle}
  on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && handleToggle()}
  tabindex="0"
  role="button"
  aria-expanded={collapsed ? 'false' : 'true'}
  aria-controls={"thread-body-" + threadId}
>
  <div class="chevron-cell">
    <Icon icon="mdi:chevron-right" style="color: var(--chat-text);" />
  </div>

  <div class="content-cell">
    <span class="tag">{tag}: </span>
    <div class="content-preview search-exclude">
      {#if previewTree}
        <HastRenderer tree={previewTree} plugins={rendererPlugins} />
      {:else}
        <span>...</span>
      {/if}
    </div>
  </div>

  <div class="meta-cell">
    <ThreadMeta
      adds={adds}
      dels={dels}
      showEdits={showEdits}
      msgLabel={msgLabel}
      totalLines={totalLines}
      threadId={threadId}
      taskSequence={taskSequence}
      allowDelete={allowDelete}
      compressed={compressed}
      showSummaryOnly={showSummary}
      hasMessages={hasMessages}
      onCopy={onCopy}
      onDelete={onDelete}
      onSetViewMode={onSetViewMode}
    />
  </div>
</header>

<style>
  /* --- Collapsed Header Preview --- */
  .header-preview {
    display: grid;
    grid-template-columns: auto 1fr minmax(var(--thread-meta-min, 240px), auto);
    align-items: center;
    gap: 0.8em;
    cursor: pointer;
    user-select: none;
    background-color: var(--message-background);
    border-left: 4px solid var(--border-color-hex);
    color: var(--chat-text);
    padding: 0.6em 1.5em;
    line-height: 1.5;
    border-radius: 0.9em;
  }
  .header-preview:hover {
    background: color-mix(in srgb, var(--chat-background) 50%, var(--message-background));
  }

  /* High contrast mode: add dotted border to collapsed thread header */
  :global(.theme-high-contrast) .header-preview {
    border: 1px dotted rgba(230, 230, 230, 0.3);
    border-left: 4px solid var(--border-color-hex);
  }

  .tag {
    font-weight: 600;
  }
  .content-cell {
    min-width: 0; /* allow preview to shrink */
    display: flex;
    gap: 0.5em;
    align-items: center;
  }
  .content-preview {
    min-width: 0; /* critical for ellipsis inside grid */
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    height: 1.5em; /* line-height */
  }
  .meta-cell {
    min-width: var(--thread-meta-min, 240px);
    display: flex;
    align-items: center;
    justify-content: flex-end;
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

  /* Hide copy/delete by default; show on hover/focus of the collapsed header */
  .header-preview :global(.delete-btn) {
    opacity: 0;
    pointer-events: none;
    transition: opacity 120ms ease-in-out;
  }
  .header-preview:hover :global(.delete-btn),
  .header-preview:focus-within :global(.delete-btn) {
    opacity: 1;
    pointer-events: auto;
  }
</style>
