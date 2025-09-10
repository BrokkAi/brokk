<script lang="ts">
    import type {HistoryTask} from '../types';
    import {toggleTaskCollapsed} from '../stores/historyStore';
    import MessageBubble from './MessageBubble.svelte';
    import Icon from '@iconify/svelte';
    import HastRenderer from './HastRenderer.svelte';
    import {rendererPlugins} from '../lib/renderer-plugins';
    import {getBubbleDisplayDefaults} from '../lib/bubble-utils';

    export let task: HistoryTask;

    $: firstEntry = task.entries[0];
    // Fallback for tasks with no entries, using the title.
    $: contentToRender = firstEntry ?? { type: 'SYSTEM', markdown: task.title, seq: -1, streaming: false };

    $: defaults = getBubbleDisplayDefaults(contentToRender.type);
    $: bubbleDisplay = { tag: defaults.title, hlVar: defaults.hlVar };
</script>

<style>
    .header {
        display: grid;
        grid-template-columns: auto auto 1fr auto;
        align-items: center;
        gap: 0.8em;
        cursor: pointer;
        user-select: none;

        /* Bubble look-and-feel */
        background-color: var(--message-background);
        border-left: 4px solid var(--border-color-hex); /* color will be set via style prop */
        color: var(--chat-text);
        padding: 0.6em 1.1em;
        line-height: 1.5;
        border-radius: 0.9em;
    }

    .header:hover {
        background: color-mix(in srgb, var(--chat-background) 50%, var(--message-background));
    }

    .tag {
        font-weight: 600;
    }

    .content-preview {
        /* single line clamp */
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        height: 1.5em; /* line-height */
        display: block;
    }

    /* Make block elements from markdown inline for the preview */
    .content-preview :global(p) {
        margin: 0;
        display: inline;
    }
    .content-preview :global(h1),
    .content-preview :global(h2),
    .content-preview :global(h3),
    .content-preview :global(ul),
    .content-preview :global(ol),
    .content-preview :global(pre),
    .content-preview :global(blockquote),
    .content-preview :global(table),
    .content-preview :global(hr) {
        display: inline;
        font-size: 1em; /* normalize font size */
        border: none;
        padding: 0;
        margin: 0;
        font-weight: normal;
    }
    .content-preview :global(li) {
        display: inline;
    }
    .content-preview :global(code) {
        font-size: 1em;
    }

    .message-count {
        font-size: 0.9em;
        color: var(--badge-foreground);
    }

    .task-body {
        padding-left: 1.5em; /* indent to align with content */
        display: flex;
        flex-direction: column;
        gap: 1em;
        padding-top: 0.5em;
    }
</style>

<div class="task-panel">
    <div
        class="header"
        style="border-left-color: var({bubbleDisplay.hlVar});"
        on:click={() => toggleTaskCollapsed(task.sequence)}
        on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggleTaskCollapsed(task.sequence)}
        tabindex="0"
        role="button"
        aria-expanded={!task.isCollapsed}
        aria-controls="task-body-{task.sequence}"
    >
        <Icon icon={task.isCollapsed ? 'mdi:chevron-right' : 'mdi:chevron-down'} style="color: inherit;" />

        {#if task.isCollapsed}
            <span class="tag">{bubbleDisplay.tag}</span>
            <div class="content-preview">
                {#if contentToRender.hast}
                    <HastRenderer tree={contentToRender.hast} plugins={rendererPlugins} />
                {:else}
                    <!-- Fallback for when hast is not ready or for non-markdown content -->
                    {contentToRender.markdown}
                {/if}
            </div>
        {/if}

        {#if task.messageCount > 1}
            <span class="message-count">{task.messageCount} msgs</span>
        {/if}
    </div>

    {#if !task.isCollapsed}
        <div class="task-body" id="task-body-{task.sequence}">
            {#each task.entries as bubble (bubble.seq)}
                <MessageBubble {bubble} />
            {/each}
        </div>
    {/if}
</div>
