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

    export let onCopy: ((threadId: number) => void) | undefined;
    export let onDelete: ((threadId: number) => void) | undefined;


    let copied = false;
    let copyResetTimer: ReturnType<typeof setTimeout> | null = null;

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
        // Provide lightweight visual feedback and call onCopy prop.
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
</script>

<span class="thread-meta">
    <span class="meta-text">
        {msgLabel} • {totalLines} lines
        {#if showEdits}
            <span class="sep">•</span>
            <span class="adds">+{adds}</span>
            <span class="dels">-{dels}</span>
        {/if}
    </span>

    <button
        type="button"
        class="delete-btn"
        class:copied={copied}
        on:click|stopPropagation|preventDefault={handleCopy}
        on:keydown|stopPropagation={() => {}}
        aria-label="Copy thread"
        title="Copy thread"
    >
        <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} style={copied ? 'color: var(--diff-add);' : ''} />
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
            <Icon icon="mdi:delete-outline" style="color: var(--diff-del);" />
        </button>
    {:else}
        <span class="spacer" />
    {/if}
</span>

<style>
    .thread-meta {
        font-size: 0.9em;
        color: var(--badge-foreground);
        white-space: nowrap;
        display: inline-flex;
        align-items: center;
        gap: 0.25em;
    }

    .meta-text {
        display: inline-flex;
        align-items: center;
        gap: 0.15em;
    }

    .thread-meta .adds {
        color: var(--diff-add);
        margin-left: 0.1em;
    }

    .thread-meta .dels {
        color: var(--diff-del);
        margin-left: 0.35em;
    }

    .thread-meta .sep {
        color: var(--badge-foreground);
        margin: 0 0.3em;
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