<script lang="ts">
    import Icon from '@iconify/svelte';
    import { createEventDispatcher } from 'svelte';

    export let adds: number;
    export let dels: number;
    export let showEdits: boolean;
    export let msgLabel: string;
    export let totalLines: number;
    export let taskSequence: number | undefined;

    // Thread identifier to include in copy-thread event detail.
    // Detail contains only { threadId }; parent component assembles the text to copy.
    export let threadId: number | undefined;

    export let onDelete: ((e: MouseEvent) => void) | undefined;

    const dispatch = createEventDispatcher<{ 'copy-thread': { threadId: number | undefined } }>();

    let copied = false;
    let copyResetTimer: ReturnType<typeof setTimeout> | null = null;

    function handleDelete(e: MouseEvent) {
        if (onDelete) {
            onDelete(e);
        }
    }

    function handleCopy(e: MouseEvent) {
        // Provide lightweight visual feedback and dispatch event upward.
        if (copyResetTimer) {
            clearTimeout(copyResetTimer);
            copyResetTimer = null;
        }
        copied = true;
        dispatch('copy-thread', { threadId });
        copyResetTimer = setTimeout(() => {
            copied = false;
            copyResetTimer = null;
        }, 800);
    }
</script>

<span class="thread-meta">
  {#if showEdits}
    <span class="adds">+{adds}</span>
    <span class="dels">-{dels}</span>
    <span class="sep">•</span>
  {/if}
    {msgLabel} • {totalLines} lines
    {#if taskSequence !== undefined}
    <button
            type="button"
            class="delete-btn"
            on:click|stopPropagation|preventDefault={handleCopy}
            aria-label="Copy thread"
            title="Copy thread"
    >
      <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} style={copied ? 'color: var(--diff-add);' : ''}/>
    </button>
    <button
            type="button"
            class="delete-btn"
            on:click|stopPropagation|preventDefault={handleDelete}
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
