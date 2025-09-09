<script lang="ts">
    import type {HistoryTask} from '../types';
    import {toggleTaskCollapsed} from '../stores/historyStore';
    import MessageBubble from './MessageBubble.svelte';
    import Icon from '@iconify/svelte';

    export let task: HistoryTask;
</script>

<style>
    .task-panel {
        border: 1px solid var(--border-color-hex);
        border-radius: 5px;
        background: var(--task-history-background);
    }

    .task-header {
        display: flex;
        align-items: center;
        padding: 0.4em 0.8em;
        cursor: pointer;
        user-select: none;
        gap: 0.8em;
    }

    .task-header:hover {
        background: color-mix(in srgb, var(--chat-background) 50%, var(--message-background));
    }

    .task-title {
        font-weight: bold;
        color: var(--chat-header-text);
        flex-grow: 1;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .message-count {
        font-size: 0.8em;
        color: var(--badge-foreground);
        background: var(--chat-background);
        padding: 0.1em 0.5em;
        border-radius: 5px;
        margin-left: auto; /* Push to the right */
        flex-shrink: 0;
        border: 1px solid var(--badge-border);
    }

    .task-body {
        padding: 2.5em;
        border-top: 1px solid var(--border-color-hex);
        display: flex;
        flex-direction: column;
        gap: 1em;
    }
</style>

<div class="task-panel">
    <div class="task-header" on:click={() => toggleTaskCollapsed(task.sequence)}
         on:keydown={(e) => e.key === 'Enter' && toggleTaskCollapsed(task.sequence)} tabindex="0" role="button"
         aria-expanded={!task.isCollapsed} aria-controls="task-body-{task.sequence}">
        <Icon icon={task.isCollapsed ? 'mdi:chevron-right' : 'mdi:chevron-down'} style="color: var(--chat-header-text);" />
        <span class="task-title" title={task.title}>{task.title}</span>
        <span class="message-count">{task.messageCount} msgs</span>
    </div>

    {#if !task.isCollapsed}
        <div class="task-body" id="task-body-{task.sequence}">
            {#each task.entries as bubble (bubble.seq)}
                <MessageBubble {bubble}/>
            {/each}
        </div>
    {/if}
</div>
