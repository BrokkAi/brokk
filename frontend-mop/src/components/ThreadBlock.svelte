<script lang="ts">
    import type { BubbleState } from '../types';
    import { threadStore } from '../stores/threadStore';
    import { summaryViewStore } from '../stores/summaryViewStore';
    import { summaryParseStore } from '../stores/summaryParseStore';
    import { getBubbleDisplayDefaults } from '../lib/bubble-utils';
    import { deleteHistoryTaskByThreadId } from '../stores/historyStore';
    import { deleteLiveTaskByThreadId } from '../stores/bubblesStore';

    import ThreadHeaderPreview from './ThreadHeaderPreview.svelte';
    import ThreadBody from './ThreadBody.svelte';

    export let threadId: number;
    export let bubbles: BubbleState[];
    // Optional, present for history threads
    export let taskSequence: number | undefined;
    // Optional, indicates if this task has been compressed (summary available)
    export let compressed: boolean = false;
    // Optional, the summary text for compressed history tasks
    export let summary: string | undefined = undefined;

    // Stores-derived state
    $: collapsed = $threadStore[threadId] ?? false;
    $: showSummary = compressed && !!summary && $summaryViewStore[threadId] === 'summary';

    // All bubbles are message bubbles (summary is on the task, not in entries)
    $: messageBubbles = bubbles;
    $: firstMessageBubble = messageBubbles[0];
    $: remainingMessageBubbles = messageBubbles.slice(1);

    // Header tag/label
    $: defaults = getBubbleDisplayDefaults(firstMessageBubble?.type ?? 'USER');
    $: headerTag = showSummary ? 'Summary' : defaults.title;

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

    // Header preview HAST: summary if in summary view, else first message
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
    <ThreadHeaderPreview
        collapsed={collapsed}
        compressed={compressed}
        tag={headerTag}
        previewTree={previewHast}
        adds={threadTotals.adds}
        dels={threadTotals.dels}
        showEdits={showEdits}
        msgLabel={msgLabel}
        totalLines={totalLinesAll}
        threadId={threadId}
        taskSequence={taskSequence}
        allowDelete={allowDelete}
        showSummary={showSummary}
        onToggleCollapse={toggle}
        onToggleSummary={toggleSummaryView}
        onCopy={handleCopy}
        onDelete={handleDelete}
    />

    {#if !collapsed}
    <div class="thread-body" id={"thread-body-" + threadId}>
        <ThreadBody
            threadId={threadId}
            taskSequence={taskSequence}
            allowDelete={allowDelete}
            compressed={compressed}
            showSummary={showSummary}
            summaryTree={$summaryParseStore[threadId]?.tree}
            summaryText={summary}
            firstMessageBubble={firstMessageBubble}
            remainingMessageBubbles={remainingMessageBubbles}
            adds={threadTotals.adds}
            dels={threadTotals.dels}
            showEdits={showEdits}
            msgLabel={msgLabel}
            totalLines={totalLinesAll}
            onToggleCollapse={toggle}
            onToggleSummary={toggleSummaryView}
            onCopy={handleCopy}
            onDelete={handleDelete}
        />
    </div>
    {/if}
</div>

<style>
    /* Visibility rules: header shown when collapsed, body only mounted when expanded */
    :global(.thread-block[data-collapsed="false"] .header-preview) {
        display: none;
    }

    /* Apply distinct border color for compressed threads */
    :global(.thread-block[data-compressed="true"] .header-preview) {
        border-left-color: var(--summary-border-color, #9b59b6);
    }
</style>
