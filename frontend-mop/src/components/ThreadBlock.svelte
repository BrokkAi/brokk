<script lang="ts">
    import type { BubbleState } from '../types';
    import { threadStore } from '../stores/threadStore';
    import { summaryViewStore } from '../stores/summaryViewStore';
    import { summaryParseStore, getSummaryParseEntry } from '../stores/summaryParseStore';
    import MessageBubble from './MessageBubble.svelte';
    import AIReasoningBubble from './AIReasoningBubble.svelte';
    import Icon from '@iconify/svelte';
    import HastRenderer from './HastRenderer.svelte';
    import { rendererPlugins } from '../lib/renderer-plugins';
    import { getBubbleDisplayDefaults } from '../lib/bubble-utils';
    import { deleteHistoryTaskByThreadId } from '../stores/historyStore';
    import { deleteLiveTaskByThreadId } from '../stores/bubblesStore';
    import ThreadMeta from './ThreadMeta.svelte';

    export let threadId: number;
    export let bubbles: BubbleState[];
    // Optional, present for history threads
    export let taskSequence: number | undefined;
    // Optional, indicates if this task has been compressed (summary available)
    export let compressed: boolean = false;
    // Optional, the summary text for compressed history tasks
    export let summary: string | undefined = undefined;

    $: collapsed = $threadStore[threadId] ?? false;
    $: showSummary = compressed && !!summary && $summaryViewStore[threadId] === 'summary';

    // Message bubbles are the conversation entries (no synthetic summary bubble)
    $: messageBubbles = bubbles;
    $: firstMessageBubble = messageBubbles[0];
    $: remainingMessageBubbles = messageBubbles.slice(1);

    $: defaults = getBubbleDisplayDefaults(firstMessageBubble?.type ?? 'USER');
    $: bubbleDisplay = { tag: defaults.title, hlVar: defaults.hlVar };

    // Determine if any bubble is currently streaming
    $: hasStreaming = bubbles.some((b) => b.streaming);
    // Allow delete for history tasks, or for current task once it is not streaming
    $: allowDelete = (taskSequence !== undefined) || !hasStreaming;

    // Aggregate diff metrics from message bubbles only (exclude summary)
    $: threadTotals = messageBubbles.reduce(
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
    $: totalLinesAll = messageBubbles.reduce((acc, b) => acc + ((b.markdown ?? '').split(/\r?\n/).length), 0);

    // Message count label (message bubbles only)
    $: msgLabel = messageBubbles.length === 1 ? '1 msg' : `${messageBubbles.length} msgs`;

    // Show edits only if any adds/dels present
    $: showEdits = threadTotals.adds > 0 || threadTotals.dels > 0;

    // Get the preview HAST: summary if in summary view, else first message
    $: previewHast = showSummary
        ? $summaryParseStore[threadId]?.tree
        : firstMessageBubble?.hast;

    function toggle() {
        threadStore.toggleThread(threadId);
    }

    function toggleSummaryView() {
        summaryViewStore.toggleViewMode(threadId);
    }

    function handleDelete(threadIdParam: number) {
        deleteHistoryTaskByThreadId(threadIdParam);
        deleteLiveTaskByThreadId(threadIdParam);
    }

    async function handleCopy() {
        const xml = showSummary
            ? `<message type="system">\n${summary}\n</message>`
            : messageBubbles
                .map(b => {
                    const t = b.type.toLowerCase();
                    return `<message type="${t}">\n${b.markdown}\n</message>`;
                })
                .join('\n\n');

        try {
            await navigator.clipboard.writeText(xml);
        } catch (serr) {
            try {
                const ta = document.createElement('textarea');
                ta.value = xml;
                ta.setAttribute('readonly', '');
                ta.style.position = 'absolute';
                ta.style.left = '-9999px';
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                document.body.removeChild(ta);
            } catch {
                // no-op
            }
        }
    }
</script>

<div class="thread-block" data-thread-id={threadId} data-collapsed={collapsed} data-compressed={compressed}>
    <!-- Collapsed header preview (always rendered; hidden when expanded via CSS) -->
    <header
        class="header-preview"
        style={`border-left-color: var(${compressed ? '--summary-border-color' : bubbleDisplay.hlVar});`}
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
            {#if previewHast}
                <HastRenderer tree={previewHast} plugins={rendererPlugins} />
            {:else}
                <span>...</span>
            {/if}
        </div>
        <ThreadMeta
            adds={threadTotals.adds}
            dels={threadTotals.dels}
            showEdits={showEdits}
            msgLabel={msgLabel}
            totalLines={totalLinesAll}
            threadId={threadId}
            taskSequence={taskSequence}
            allowDelete={allowDelete}
            compressed={compressed}
            showSummaryOnly={showSummary}
            onCopy={handleCopy}
            onDelete={handleDelete}
            onToggleSummary={toggleSummaryView}
        />
    </header>

    <!-- Thread body (NOT mounted when collapsed to avoid unnecessary DOM) -->
    {#if !collapsed}
    <div class="thread-body" id={"thread-body-" + threadId}>
        <div class="first-bubble-wrapper">
            <button
                type="button"
                class="toggle-arrow-btn"
                on:click={toggle}
                on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
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
            <div class="bubble-container">
                <div class="thread-meta-inline">
                    <div class="meta-actions">
                        <ThreadMeta
                            adds={threadTotals.adds}
                            dels={threadTotals.dels}
                            showEdits={showEdits}
                            msgLabel={msgLabel}
                            totalLines={totalLinesAll}
                            threadId={threadId}
                            taskSequence={taskSequence}
                            allowDelete={allowDelete}
                            compressed={compressed}
                            showSummaryOnly={showSummary}
                            onCopy={handleCopy}
                            onDelete={handleDelete}
                            onToggleSummary={toggleSummaryView}
                        />
                    </div>
                </div>

                <div
                    class="first-line-hit-area"
                    on:click={toggle}
                    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
                    tabindex="0"
                    role="button"
                    aria-expanded="true"
                    aria-controls={"thread-body-" + threadId}
                    aria-label="Collapse thread"
                ></div>

                {#if showSummary}
                    <!-- Render summary as a system message -->
                    <div class="summary-renderer">
                        {#if $summaryParseStore[threadId]?.tree}
                            <HastRenderer tree={$summaryParseStore[threadId].tree} plugins={rendererPlugins} />
                        {:else}
                            <p>{summary}</p>
                        {/if}
                    </div>
                {:else}
                    <!-- Render message bubbles -->
                    {#if firstMessageBubble.type === 'AI' && firstMessageBubble.reasoning}
                        <AIReasoningBubble bubble={firstMessageBubble} />
                    {:else}
                        <MessageBubble bubble={firstMessageBubble} />
                    {/if}
                {/if}
            </div>
        </div>

        {#if !showSummary && remainingMessageBubbles.length > 0}
            <div class="remaining-bubbles">
                {#each remainingMessageBubbles as bubble (bubble.seq)}
                    {#if bubble.type === 'AI' && bubble.reasoning}
                        <AIReasoningBubble {bubble} />
                    {:else}
                        <MessageBubble {bubble} />
                    {/if}
                {/each}
            </div>
        {/if}
    </div>
    {/if}
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

    /* High contrast mode: add dotted border to collapsed thread header */
    :global(.theme-high-contrast) .header-preview {
        border: 1px dotted rgba(230, 230, 230, 0.3);
        border-left: 4px solid var(--border-color-hex);
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

    /* Visibility rules: header shown when collapsed, body only mounted when expanded */
    .thread-block[data-collapsed="false"] .header-preview {
        display: none;
    }

    /* Expanded inline metadata overlay aligned with the first line (tag row) */
    .thread-meta-inline {
        position: absolute;
        top: -10px;
        right: 40px;
        z-index: 3; /* Above the first-line hit area (z-index:1) so delete is clickable */
        height: var(--thread-first-line-hit-height, 2.25em);
        display: flex;
        align-items: center;
        justify-content: flex-end;
        padding-left: 0.5em;
        padding-right: 0.2em;
        pointer-events: none; /* Let clicks fall through except on the delete button */
    }


    .thread-meta-inline .meta-actions {
        pointer-events: auto;
        display: inline-flex;
        align-items: center;
        height: 100%;
    }

    /* Apply distinct border color for compressed threads */
    .thread-block[data-compressed="true"] .header-preview {
        border-left-color: var(--summary-border-color, #9b59b6);
    }

    /* Summary renderer styling */
    .summary-renderer {
        padding: 1em;
        background-color: var(--message-background);
        border-radius: 0.9em;
        border-left: 4px solid var(--summary-border-color, #9b59b6);
    }
</style>
