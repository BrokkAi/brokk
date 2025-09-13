<script lang="ts">
    import type { BubbleState } from '../types';
    import { threadStore } from '../stores/threadStore';
    import MessageBubble from './MessageBubble.svelte';
    import AIReasoningBubble from './AIReasoningBubble.svelte';
    import Icon from '@iconify/svelte';
    import HastRenderer from './HastRenderer.svelte';
    import { rendererPlugins } from '../lib/renderer-plugins';
    import { getBubbleDisplayDefaults } from '../lib/bubble-utils';

    export let threadId: number;
    export let bubbles: BubbleState[];

    $: collapsed = $threadStore[threadId] ?? false;

    $: firstBubble = bubbles[0];
    $: remainingBubbles = bubbles.slice(1);

    $: defaults = getBubbleDisplayDefaults(firstBubble.type);
    $: bubbleDisplay = { tag: defaults.title, hlVar: defaults.hlVar };

    function toggle() {
        threadStore.toggleThread(threadId);
    }
</script>

<div class="thread-block" data-thread-id={threadId} data-collapsed={collapsed}>
    <!-- Collapsed header preview (always rendered; hidden when expanded via CSS) -->
    <header
        class="header-preview"
        style="border-left-color: var({bubbleDisplay.hlVar});"
        on:click={toggle}
        on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
        tabindex="0"
        role="button"
        aria-expanded={collapsed ? 'false' : 'true'}
        aria-controls="thread-body-{threadId}"
    >
        <Icon icon="mdi:chevron-right" style="color: var(--chat-text);" />
        <span class="tag">{bubbleDisplay.tag}: </span>
        <div class="content-preview search-exclude">
            {#if firstBubble.hast}
                <HastRenderer tree={firstBubble.hast} plugins={rendererPlugins} />
            {:else}
                <span>...</span>
            {/if}
        </div>
        {#if bubbles.length > 1}
            <span class="message-count">{bubbles.length} msgs</span>
        {/if}
    </header>

    <!-- Thread body (always rendered; visually collapsed via CSS when data-collapsed="true") -->
    <div class="thread-body" id="thread-body-{threadId}">
        <div
            class="first-bubble-wrapper"
            on:click={toggle}
            on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
            tabindex="0"
            role="button"
            aria-expanded={collapsed ? 'false' : 'true'}
            aria-controls="thread-body-{threadId}"
        >
            <Icon icon="mdi:chevron-down" class="toggle-arrow" style="color: var(--chat-text);" />
            <div class="bubble-container">
                {#if firstBubble.type === 'AI' && firstBubble.reasoning}
                    <AIReasoningBubble bubble={firstBubble} />
                {:else}
                    <MessageBubble bubble={firstBubble} />
                {/if}
            </div>
        </div>

        {#if remainingBubbles.length > 0}
            <div class="remaining-bubbles">
                {#each remainingBubbles as bubble (bubble.seq)}
                    {#if bubble.type === 'AI' && bubble.reasoning}
                        <AIReasoningBubble {bubble} />
                    {:else}
                        <MessageBubble {bubble} />
                    {/if}
                {/each}
            </div>
        {/if}
    </div>
</div>

<style>
    /* --- Collapsed Header Preview --- */
    .header-preview {
        display: grid;
        grid-template-columns: auto auto 1fr auto;
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
    }
    .header-preview:hover {
        background: color-mix(in srgb, var(--chat-background) 50%, var(--message-background));
    }
    .tag {
        font-weight: 600;
    }
    .content-preview {
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        height: 1.5em; /* line-height */
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
    .message-count {
        font-size: 0.9em;
        color: var(--badge-foreground);
    }

    /* --- Expanded View --- */
    .first-bubble-wrapper {
        display: flex;
        align-items: flex-start;
        gap: 0.5em;
        cursor: pointer;
        border-radius: 0.9em; /* To provide a hover/focus area */
        padding-bottom: 1em;
    }
    .first-bubble-wrapper:hover {
       background: color-mix(in srgb, var(--chat-background) 50%, transparent);
    }
    .toggle-arrow {
        flex-shrink: 0;
        margin-top: 0.7em;
        color: var(--chat-text);
    }
    .bubble-container {
        flex-grow: 1;
        width: 100%;
    }

    .remaining-bubbles {
        display: flex;
        flex-direction: column;
        gap: 1em;
        padding-left: 1.7em; /* Indent to align with first bubble content */
    }

    /* Visibility rules to keep DOM mounted while preserving visuals */
    .thread-block[data-collapsed="false"] .header-preview {
        display: none;
    }
    .thread-block[data-collapsed="true"] .thread-body {
        max-height: 0;
        overflow: hidden;
        padding: 0;
        margin: 0;
    }
</style>
