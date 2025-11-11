<script lang="ts">
    import Icon from '@iconify/svelte';
    import {expandDiff, collapseDiff} from '../worker/worker-bridge';
    import { findMarkdownBySeq } from '../stores/lookup';
    import type { EditBlockProperties } from '../worker/shared';

    let {
        id = '-1',
        filename = '?',
        adds = 0,
        dels = 0,
        search = '',
        replace = '',
        headerOk = false,
        isExpanded = false,
        bubbleId,
    } = $props<EditBlockProperties>();

    const basename = $derived(filename.split(/[\\/]/).pop() ?? filename);

    // Show state: open when worker marks the block expanded; otherwise stay as user chooses.
    let showDetails = $state(isExpanded);

    // Track previous expanded prop to detect rising edge without reading/writing showDetails in same effect
    let prevIsExpanded = isExpanded;

    // One-way sync: if worker decides expanded (auto/manual), open once on rising edge.
    $effect(() => {
        if (isExpanded && !prevIsExpanded) {
            showDetails = true;
        }
        prevIsExpanded = isExpanded;
    });

    function toggleDetails() {
        showDetails = !showDetails;
        const markdown = findMarkdownBySeq(bubbleId) ?? '';
        if (showDetails) {
            // User opened: expand in worker and render body
            expandDiff(markdown, bubbleId, id);
        } else {
            // User closed: persist collapse to suppress auto-expansion and avoid worker render
            collapseDiff(markdown, bubbleId, id);
        }
    }

</script>

{#if headerOk}
    <div class="edit-block-wrapper">
        <header class="edit-block-header" on:click={toggleDetails}>
            <Icon icon="mdi:file-document-edit-outline" class="file-icon"/>
            <span class="filename" title={filename}>{basename}</span>
            <div class="stats">
                {#if adds > 0}
                    <span class="adds">+{adds}</span>
                {/if}
                {#if dels > 0}
                    <span class="dels">-{dels}</span>
                {/if}
            </div>
            <div class="spacer"></div>

            {#if showDetails}
                <Icon icon="mdi:chevron-up" class="toggle-icon" />
            {/if}
            {#if !showDetails}
                <Icon icon="mdi:chevron-down" class="toggle-icon" />
            {/if}
        </header>
        <!-- Use two icons instead of toggling a single Iconify prop to avoid an intermittent @iconify/svelte update crash (“null attributes”) during rapid subtree updates when edit-blocks auto-expand.
        This forces a clean unmount/mount and prevents the render flush from aborting (which made the next bubble look empty).
        Do not refactor unless Iconify fixes this or you switch to a single static icon with CSS rotation. -->
        {#if showDetails}
            <div class="edit-block-body">
                <slot></slot>
            </div>
        {/if}
    </div>
{/if}

<style>
    .edit-block-wrapper {
        border: 1px solid var(--message-border-custom);
        border-radius: 8px;
        margin: 1em 0;
        overflow: hidden;
        background-color: var(--code-block-background);
    }

    .edit-block-header {
        display: flex;
        align-items: center;
        padding: 0.5em 0.8em;
        cursor: pointer;
        user-select: none;
        background-color: color-mix(in srgb, var(--code-block-background) 85%, var(--message-border-custom));
    }

    .edit-block-header:hover {
        background-color: color-mix(in srgb, var(--code-block-background) 75%, var(--message-border-custom));
    }

    .file-icon {
        margin-right: 0.5em;
        color: var(--chat-text);
    }

    .filename {
        font-weight: 600;
        font-family: monospace;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        min-width: 0; /* Allow shrinking for ellipsis to work in flex */
    }

    .stats {
        margin-left: 1em;
        display: flex;
        gap: 0.75em;
        font-family: monospace;
        font-size: 0.9em;
    }

    .adds {
        color: var(--diff-add);
    }

    .dels {
        color: var(--diff-del);
    }

    .spacer {
        flex-grow: 1;
    }

    .toggle-icon {
        color: var(--chat-text);
        opacity: 0.7;
    }

    .edit-block-body {
        font-size: 0.85em;
    }

    .edit-block-body :global(pre) {
        margin: 0;
        /* Shiki adds a background color, which is fine. */
        /* It also adds horizontal padding, which we override on lines. */
        padding: 0;
        white-space: inherit;
        font-size: 0;
    }

    .edit-block-body :global(.diff-line) {
        display: block;
        padding-left: 0.8em;
        padding-right: 0.8em;
        font-size: calc(0.9 * 14px * var(--zoom-level, 1));
        line-height: 1.4;
    }

    .edit-block-body :global(.diff-add) {
        background-color: var(--diff-add-bg);
    }

    .edit-block-body :global(.diff-del) {
        background-color: var(--diff-del-bg);
    }

    /* Diff markers (+/-) before each line using CSS-only */
    .edit-block-body :global(.diff-line)::before {
        content: '';
        display: inline-block;
        width: 1.25ch;           /* gutter width for marker */
        margin-right: 0.5ch;     /* space between marker and code */
        font-family: monospace;  /* align with code */
        opacity: 0.9;
    }

    .edit-block-body :global(.diff-add)::before {
        content: '+';
        color: var(--diff-add);
    }

    .edit-block-body :global(.diff-del)::before {
        content: '-';
        color: var(--diff-del);
    }

</style>
