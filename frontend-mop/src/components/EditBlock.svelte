<script lang="ts">
  import Icon from "@iconify/svelte";

  let {
    filename = '?',
    adds = '0',
    dels = '0',
    changed = '0',
    status = 'UNKNOWN',
    search = '',
    replace = ''
  } = $props();

  const numAdds = +adds;
  const numDels = +dels;

  let showDetails = $state(false);

  const toggleDetails = () => {
    showDetails = !showDetails;
  }
</script>

<div class="edit-block-wrapper">
    <header class="edit-block-header" on:click={toggleDetails}>
        <Icon icon="mdi:file-document-edit-outline" class="file-icon"/>
        <span class="filename">{filename}</span>
        <div class="stats">
            {#if numAdds > 0}
            <span class="adds">+{numAdds}</span>
            {/if}
            {#if numDels > 0}
            <span class="dels">-{numDels}</span>
            {/if}
        </div>
        <div class="spacer"></div>
        <Icon icon={showDetails ? 'mdi:chevron-up' : 'mdi:chevron-down'} class="toggle-icon"/>
    </header>

    {#if showDetails}
    <div class="edit-block-body">
        {#if search.trim()}
        <div class="diff-section">
            <pre class="search-code">{search.trim()}</pre>
        </div>
        {/if}
        {#if search.trim() && replace.trim()}
        <div class="diff-separator"></div>
        {/if}
        {#if replace.trim()}
        <div class="diff-section">
            <pre class="replace-code">{replace.trim()}</pre>
        </div>
        {/if}
    </div>
    {/if}
</div>

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
        padding: 0;
    }
    .diff-separator {
        border-top: 1px dashed var(--message-border-custom);
        margin: 0 0.8em;
    }
    pre {
        margin: 0;
        padding: 0.8em;
        white-space: pre-wrap;
        word-break: break-all;
        font-family: monospace;
        font-size: 0.85em;
    }
    .search-code {
        background-color: var(--diff-del-bg);
        color: var(--chat-text);
        position: relative;
        padding-left: 2em;
    }
    .search-code::before {
        content: '-';
        position: absolute;
        left: 0.8em;
        color: var(--diff-del);
    }
    .replace-code {
        background-color: var(--diff-add-bg);
        color: var(--chat-text);
        position: relative;
        padding-left: 2em;
    }
    .replace-code::before {
        content: '+';
        position: absolute;
        left: 0.8em;
        color: var(--diff-add);
    }
</style>
