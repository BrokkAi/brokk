<script lang="ts">
    import Icon from '@iconify/svelte';
    import { onDestroy } from 'svelte';

    export let adds: number;
    export let dels: number;
    export let showEdits: boolean;
    export let msgLabel: string;
    export let totalLines: number;
    export let threadId: number;
    export let taskSequence: number | undefined;
    export let allowDelete: boolean = false;
    export let compressed: boolean = false;
    export let showSummaryOnly: boolean = false;

    export let onCopy: ((threadId: number) => void) | undefined;
    export let onDelete: ((threadId: number) => void) | undefined;
    export let onSetViewMode: ((mode: 'messages' | 'summary') => void) | undefined;
    export let hasMessages: boolean = true;

    let copied = false;
    let copyResetTimer: ReturnType<typeof setTimeout> | null = null;

    let messagesBtn: HTMLButtonElement;
    let summaryBtn: HTMLButtonElement;

    onDestroy(() => {
        if (copyResetTimer) {
            clearTimeout(copyResetTimer);
            copyResetTimer = null;
        }
    });

    function handleDelete() {
        if (onDelete) {
            onDelete(threadId);
        }
    }

    function handleCopy() {
        if (copyResetTimer) {
            clearTimeout(copyResetTimer);
            copyResetTimer = null;
        }
        copied = true;
        if (onCopy) {
            onCopy(threadId);
        }
        copyResetTimer = setTimeout(() => {
            copied = false;
            copyResetTimer = null;
        }, 800);
    }

    function setMode(mode: 'messages' | 'summary') {
        if (onSetViewMode) {
            onSetViewMode(mode);
        }
    }

    function handleGroupKeydown(e: KeyboardEvent) {
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
        e.preventDefault();
        e.stopPropagation();
        const current = showSummaryOnly ? 'summary' : 'messages';
        const next = e.key === 'ArrowLeft' ? 'messages' : 'summary';
        if (next !== current) {
            setMode(next);
            // move focus to the newly selected segment
            if (next === 'messages' && messagesBtn) messagesBtn.focus();
            if (next === 'summary' && summaryBtn) summaryBtn.focus();
        }
    }

    function handleSegmentKeydown(e: KeyboardEvent, mode: 'messages' | 'summary') {
        if (e.key === ' ' || e.key === 'Enter') {
            e.preventDefault();
            e.stopPropagation();
            setMode(mode);
        }
    }
</script>

<span class="thread-meta">
  {#if showEdits}
    <span class="adds">+{adds}</span>
    <span class="dels">-{dels}</span>
    <span class="sep">•</span>
  {/if}
  {msgLabel} • {totalLines} lines

  {#if compressed && hasMessages}
    <div
      class="segmented-control"
      role="radiogroup"
      aria-label="Thread view mode"
      on:keydown|stopPropagation={handleGroupKeydown}
    >
      <button
        type="button"
        bind:this={messagesBtn}
        class="segment-btn segment-left"
        class:active={!showSummaryOnly}
        role="radio"
        aria-checked={!showSummaryOnly}
        tabindex={!showSummaryOnly ? 0 : -1}
        aria-label="Show full messages"
        title="Full messages"
        on:click|stopPropagation|preventDefault={() => setMode('messages')}
        on:keydown|stopPropagation={(e) => handleSegmentKeydown(e, 'messages')}
      >
        Full messages
      </button>
      <button
        type="button"
        bind:this={summaryBtn}
        class="segment-btn segment-right"
        class:active={showSummaryOnly}
        role="radio"
        aria-checked={showSummaryOnly}
        tabindex={showSummaryOnly ? 0 : -1}
        aria-label="Show what AI sees"
        title="What AI sees"
        on:click|stopPropagation|preventDefault={() => setMode('summary')}
        on:keydown|stopPropagation={(e) => handleSegmentKeydown(e, 'summary')}
      >
        What AI sees
      </button>
    </div>
  {/if}

  <button
    type="button"
    class="delete-btn"
    class:copied={copied}
    on:click|stopPropagation|preventDefault={handleCopy}
    on:keydown|stopPropagation={() => {}}
    aria-label="Copy thread"
    title="Copy thread"
  >
    <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} style={copied ? 'color: var(--diff-add);' : ''}/>
  </button>

  {#if allowDelete}
    <button
      type="button"
      class="delete-btn"
      on:click|stopPropagation|preventDefault={handleDelete}
      on:keydown|stopPropagation={() => {}}
      aria-label="Delete history task"
      title="Delete history task"
    >
      <Icon icon="mdi:delete-outline" style="color: var(--diff-del);"/>
    </button>
  {:else}
    <span class="spacer"/>
  {/if}
</span>

<style>
  .thread-meta {
    font-size: 0.9em;
    color: var(--badge-foreground);
    white-space: nowrap;
  }

  .thread-meta .adds {
    color: var(--diff-add);
    margin-right: 0.25em;
  }

  .thread-meta .dels {
    color: var(--diff-del);
    margin-right: 0.45em;
  }

  .thread-meta .sep {
    color: var(--badge-foreground);
    margin-right: 0.45em;
  }

  /* Segmented control */
  .segmented-control {
    display: inline-flex;
    align-items: stretch;
    margin: 0 0.4em;
    border: 1px solid var(--border-color-hex);
    border-radius: 6px; /* more rectangular than round */
    overflow: hidden;
    background: var(--badge-muted-bg, var(--message-background));
    vertical-align: middle;
  }

  .segment-btn {
    background: transparent;
    border: none;
    padding: 0.3em 0.9em;
    font-size: 0.85em;
    font-weight: 600;
    color: var(--badge-muted-fg, var(--badge-foreground));
    cursor: pointer;
    line-height: 1.2;
  }

  .segment-left {
    border-right: 1px solid color-mix(in srgb, var(--border-color-hex) 60%, transparent);
  }

  .segment-btn:hover {
    /* Subtle hint toward reasoning accent */
    background: color-mix(in srgb, var(--badge-muted-bg, var(--message-background)) 90%, var(--message-border-ai-reasoning));
  }

  .segment-btn.active {
    /* Reasoning colors for the active side */
    background: var(--message-border-ai-reasoning);
    color: var(--ai-reasoning-header-foreground);
  }

  /* High-contrast: keep strong neutral track and border */
  :global(.theme-high-contrast) .segmented-control {
    border-color: var(--border-color-hex);
    background: var(--message-background);
  }

  .segment-btn:focus-visible {
    outline: 2px solid var(--focus-ring, #5b9dd9);
    outline-offset: 2px;
    border-radius: 4px;
  }

  .delete-btn {
    background: transparent;
    border: none;
    padding: 0.25em;
    color: var(--chat-text);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 0.35em;
  }

  .delete-btn:hover {
    background: color-mix(in srgb, var(--chat-background) 70%, var(--message-background));
  }

  .delete-btn:focus-visible {
    outline: 2px solid var(--focus-ring, #5b9dd9);
    outline-offset: 2px;
  }

  .spacer {
    display: inline-block;
    width: 20px;
  }
</style>