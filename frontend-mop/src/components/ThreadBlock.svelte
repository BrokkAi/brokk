<script lang="ts">
    import type { BubbleState } from '../types';
    import { threadStore } from '../stores/threadStore';
    import MessageBubble from './MessageBubble.svelte';
    import AIReasoningBubble from './AIReasoningBubble.svelte';
    import Icon from '@iconify/svelte';
    import HastRenderer from './HastRenderer.svelte';
    import { rendererPlugins } from '../lib/renderer-plugins';
    import { getBubbleDisplayDefaults } from '../lib/bubble-utils';
    import { deleteHistoryTaskByThreadId } from '../stores/historyStore';

    export let threadId: number;
    export let bubbles: BubbleState[];
    // Optional, present for history threads
    export let taskSequence: number | undefined;

    $: collapsed = $threadStore[threadId] ?? false;

    $: firstBubble = bubbles[0];
    $: remainingBubbles = bubbles.slice(1);

    $: defaults = getBubbleDisplayDefaults(firstBubble.type);
    $: bubbleDisplay = { tag: defaults.title, hlVar: defaults.hlVar };

    // Aggregate diff metrics across all bubbles in this thread
    $: threadTotals = bubbles.reduce(
        (acc, b) => {
            const s = (b.hast as any)?.data?.diffSummary;
            if (s) {
                acc.adds += s.adds || 0;
                acc.dels += s.dels || 0;
            }
            return acc;
        },
        { adds: 0, dels: 0 }
    );

    // Lines count: total lines across all messages in this thread
    $: totalLinesAll = bubbles.reduce((acc, b) => acc + ((b.markdown ?? '').split(/\r?\n/).length), 0);

    // Message count label
    $: msgLabel = bubbles.length === 1 ? '1 msg' : `${bubbles.length} msgs`;

    // Show edits only if any adds/dels present
    $: showEdits = threadTotals.adds > 0 || threadTotals.dels > 0;

    function toggle() {
        threadStore.toggleThread(threadId);
    }

    function handleDelete(e: MouseEvent) {
        e.stopPropagation();
        e.preventDefault();
        deleteHistoryTaskByThreadId(threadId);
    }
</script>

<div class="thread-block" data-thread-id={threadId} data-collapsed={collapsed}>
    <!-- Collapsed header preview (always rendered; hidden when expanded via CSS) -->
    <header
        class="header-preview"
        style={`border-left-color: var(${bubbleDisplay.hlVar});`}
        on:click={toggle}
        on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
        tabindex="0"
        role="button"
        aria-expanded={collapsed ? 'false' : 'true'}
        aria-controls={"thread-body-" + threadId}
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
        <span class="thread-meta">
            {#if showEdits}
                <span class="adds">+{threadTotals.adds}</span>
                <span class="dels">-{threadTotals.dels}</span>
                <span class="sep">•</span>
            {/if}
            {msgLabel} • {totalLinesAll} lines
        </span>
        {#if taskSequence !== undefined}
            <button
                type="button"
                class="delete-btn"
                on:click|stopPropagation|preventDefault={handleDelete}
                aria-label="Delete history task"
                title="Delete history task"
            >
                <Icon icon="mdi:delete-outline" style="color: var(--diff-del);" />
            </button>
        {/if}
    </header>

    <!-- Thread body (always rendered; visually collapsed via CSS when data-collapsed="true") -->
    <div class="thread-body" id={"thread-body-" + threadId}>
        <div class="first-bubble-wrapper">
            <button
                type="button"
                class="toggle-arrow-btn"
                on:click={toggle}
                on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
                aria-expanded={collapsed ? 'false' : 'true'}
                aria-controls={"thread-body-" + threadId}
                aria-label="Collapse thread"
            >
                <Icon
                    icon="mdi:chevron-down"
                    class="toggle-arrow"
                    style="color: var(--chat-text);"
                />
            </button>
            <div class="bubble-container">
                {#if !collapsed}
                    <div
                        class="first-line-hit-area"
                        on:click={toggle}
                        on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
                        tabindex="0"
                        role="button"
                        aria-expanded={collapsed ? 'false' : 'true'}
                        aria-controls={"thread-body-" + threadId}
                        aria-label="Collapse thread"
                    ></div>
                {/if}
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

    /* Delete button */
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

    /* --- Expanded View --- */
    .first-bubble-wrapper {
        display: flex;
        align-items: flex-start;
        gap: 0.5em;
        border-radius: 0.9em; /* To provide a hover/focus area */
        padding-bottom: 1em;
    }
    .first-bubble-wrapper:hover {
       background: transparent;
    }
    .toggle-arrow-btn {
        flex-shrink: 0;
        margin-top: 0.5em;
        background: transparent;
        border: none;
        padding: 0;
        color: var(--chat-text);
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
    }
    .toggle-arrow-btn:focus-visible {
        outline: 2px solid var(--focus-ring, #5b9dd9);
        outline-offset: 2px;
        border-radius: 0.35em;
    }
    .toggle-arrow {
        color: var(--chat-text);
        pointer-events: none; /* ensure the button receives the click */
    }
    .bubble-container {
        flex-grow: 1;
        width: 100%;
        position: relative; /* to position the first-line hit area */
    }
    /* Transparent hit target covering the first line of the first bubble
       so clicking the "label" (e.g., "You") or that line collapses */
    .first-line-hit-area {
        position: absolute;
        z-index: 1;
        top: 0;
        left: 0;
        right: 0;
        height: var(--thread-first-line-hit-height, 2.25em);
        cursor: pointer;
        background: transparent;
    }
    .first-line-hit-area:focus-visible {
        outline: 2px solid var(--focus-ring, #5b9dd9);
        outline-offset: 2px;
        border-radius: 0.35em;
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
