<script lang="ts">
    import Icon from '@iconify/svelte';
    import ThreadMeta from './ThreadMeta.svelte';
    import SummaryContent from './SummaryContent.svelte';
    import AIReasoningBubble from './AIReasoningBubble.svelte';
    import MessageBubble from './MessageBubble.svelte';
    import BubbleList from './BubbleList.svelte';

    import type {BubbleState} from '../types';

    // Identity/controls
    export let threadId: number;
    export let taskSequence: number | undefined;
    export let allowDelete: boolean;
    export let compressed: boolean;

    // Derived flags
    export let showSummary: boolean;

    // Summary content
    export let summaryTree: any | undefined;
    export let summaryText: string | undefined;

    // Message bubbles
    export let firstMessageBubble: BubbleState | undefined;
    export let remainingMessageBubbles: BubbleState[];

    // Metrics/meta
    export let adds: number;
    export let dels: number;
    export let showEdits: boolean;
    export let msgLabel: string;
    export let totalLines: number;

    // Handlers
    export let onToggleCollapse: () => void;
    export let onToggleSummary: () => void;
    export let onCopy: () => void;
    export let onDelete: (threadId: number) => void;

    // Combine all message bubbles for full-width body rendering
    $: allMessageBubbles = firstMessageBubble
        ? [firstMessageBubble, ...remainingMessageBubbles]
        : remainingMessageBubbles;

    function handleToggle() {
        onToggleCollapse?.();
    }
</script>

<!-- Stable first row: [chevron] [content hit-area] [meta] -->
<div class="first-row">
    <button
            type="button"
            class="toggle-arrow-btn"
            on:click={handleToggle}
            on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && handleToggle()}
            aria-expanded="true"
            aria-controls={"thread-body-" + threadId}
            aria-label="Collapse thread"
    >
        <Icon
                icon="mdi:chevron-down"
                class="toggle-arrow"
                style="color: var(--chat-text);"
        />
    </button>

    <div class="content-cell">
        <!-- First line click area only; actual content is rendered in full-width below -->
        <div
                class="first-line-hit-area"
                on:click={handleToggle}
                on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && handleToggle()}
                tabindex="0"
                role="button"
                aria-expanded="true"
                aria-controls={"thread-body-" + threadId}
                aria-label="Collapse thread"
        ></div>
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
                onCopy={onCopy}
                onDelete={onDelete}
                onToggleSummary={onToggleSummary}
        />
    </div>
</div>

<!-- Full-width body content below the header row -->
<div class="expanded-content" id={"thread-body-" + threadId}>
    {#if showSummary}
        <SummaryContent tree={summaryTree} textFallback={summaryText ?? ''}/>
    {:else}
        <!-- Render all message bubbles full-width -->
        <BubbleList bubbles={allMessageBubbles}/>
    {/if}
</div>

<style>
    /* Stable grid: [chevron] [content] [meta] for alignment with collapsed headers */
    .first-row {
        display: grid;
        grid-template-columns: auto 1fr minmax(var(--thread-meta-min, 240px), auto);
        align-items: start;
        gap: 0.6em;
        padding-bottom: 0.5em;
    }

    .toggle-arrow-btn {
        flex-shrink: 0;
        margin-top: 0.5em;
        margin-left: 1.5em;
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
        pointer-events: none;
    }

    .content-cell {
        position: relative;
        min-width: 0; /* required for ellipsis and to let meta align */
    }

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

    .meta-cell {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        height: var(--thread-first-line-hit-height, 2.25em);
        min-width: var(--thread-meta-min, 240px);
        padding-right: 1.5em;
    }

    /* Full-width content under the header row; indent under the chevron */
    .expanded-content {
        display: block;
        padding-left: 1.5em; /* align with content cell under the chevron */
        padding-right: 1.5em;
    }
</style>
