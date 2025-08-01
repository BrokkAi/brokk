<script lang="ts">
    import Icon from '@iconify/svelte';
    import {expandDiff} from '../worker/worker-bridge';
    import type {BubbleState} from '../stores/bubblesStore';
    import { bubblesStore } from '../stores/bubblesStore';
    import { get } from 'svelte/store';

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

    let showDetails = $state(false);

    function toggleDetails() {
        showDetails = !showDetails;
        if (showDetails) {
            const markdown =
                get(bubblesStore).find(b => b.id === bubbleId)?.markdown ?? '';
            expandDiff(markdown, bubbleId, id);
        }
    }

</script>

{#if headerOk}
    <div class="edit-block-wrapper">
        <header class="edit-block-header" on:click={toggleDetails}>
            <Icon icon="mdi:file-document-edit-outline" class="file-icon"/>
            <span class="filename">{filename}</span>
            <div class="stats">
                {#if adds > 0}
                    <span class="adds">+{adds}</span>
                {/if}
                {#if dels > 0}
                    <span class="dels">-{dels}</span>
                {/if}
            </div>
            <div class="spacer"></div>
            <Icon icon={showDetails ? 'mdi:chevron-up' : 'mdi:chevron-down'} class="toggle-icon"/>
        </header>

        {#if showDetails}
            <div class="edit-block-body">
                <slot></slot>
            </div>
        {/if}
    </div>
{/if}

<style>
    .edit-block-wrapper {
        --diff-add: #28a745;
        --diff-del: #dc3545;
        --diff-add-bg: rgba(40, 167, 69, 0.1);
        --diff-del-bg: rgba(220, 53, 69, 0.1);
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

    .edit-block-body :global(.custom-code-block) {
        margin-left: 10px;
        margin-right: 10px;
    }

    .edit-block-body :global(pre) {
        margin: 0;
        /* Shiki adds a background color, which is fine. */
        /* It also adds horizontal padding, which we override on lines. */
        padding-top: 0.8em;
        padding-bottom: 0.8em;
        white-space: pre-wrap;
        font-size: 0;
    }

    .edit-block-body :global(.diff-line) {
        display: block;
        padding-left: 0.8em;
        padding-right: 0.8em;
        font-size: 0.8rem;
        line-height: 1.4;
    }

    .edit-block-body :global(.diff-add) {
        background-color: var(--diff-add-bg);
    }

    .edit-block-body :global(.diff-del) {
        background-color: var(--diff-del-bg);
    }

</style>
