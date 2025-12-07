<script lang="ts">
    import Icon from "@iconify/svelte";
    import BaseBubble from './BaseBubble.svelte';
    import type {BubbleState} from '../stores/bubblesStore';
    import {toggleBubbleCollapsed} from '../stores/bubblesStore';

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

<BaseBubble {bubble} {hlVar} {bgVar} collapsed={!!bubble.isCollapsed}>
    <div
        slot="header"
        class="reasoning-header {bubble.isCollapsed ? 'collapsed' : ''}"
        style:color={bubble.reasoningComplete ? 'var(--ai-reasoning-header-foreground)' : `var(${hlVar})`}
        on:click={toggleCollapse}
    >
        {#if bubble.reasoningComplete}
            <Icon icon={bubble.isCollapsed ? 'mdi:chevron-right' : 'mdi:chevron-down'}
                  style="margin-right: 0.35em;"/>
            <span class="title">
                {#if showThoughtsLabel}
                    Thoughts
                {:else}
                    Thought for {displayDuration} seconds
                {/if}
            </span>
        {:else}
            <Icon icon="mdi:loading" class="spin-icon" style="margin-right: 0.35em;"/>
            <span class="title">Reasoning progress...</span>
        {/if}
    </div>
</BaseBubble>

<style>
    .reasoning-header {
        cursor: pointer;
        user-select: none; /* Prevents text selection on click */
        display: flex;
        align-items: center;
        font-weight: 600;
        font-size: 0.95em;
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
