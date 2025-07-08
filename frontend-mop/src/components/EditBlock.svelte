<script lang="ts">
  import Icon from "@iconify/svelte";
  import { highlighterPromise, ensureLang } from '../shiki-plugin';
  import { buildUnifiedDiff, detectLang } from '../lib/diff-utils';
  import { transformerDiffLines } from '../shiki-diff-transformer';

  let {
    filename = '?',
    adds = '0',
    dels = '0',
    // changed = '0', // prop available if needed
    // status = 'UNKNOWN', // prop available if needed
    search = '',
    replace = ''
  } = $props();

  const numAdds = +adds;
  const numDels = +dels;

  let showDetails = $state(false);
  let diffHtml: string | null = $state(null);
  let isLoading = $state(false);

  async function generateDiff() {
    if (diffHtml || isLoading) return; // Execute only once

    isLoading = true;
    try {
      const { text, added, removed } = buildUnifiedDiff(search, replace);
      const lang = detectLang(filename);

      await ensureLang(lang);
      const highlighter = await highlighterPromise;

      diffHtml = await highlighter.codeToHtml(text, {
        lang: lang,
        theme: 'css-vars',
        transformers: [transformerDiffLines(added, removed)]
      });
    } catch (e) {
      console.error("Failed to generate diff:", e);
      const escape = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
      diffHtml = `<pre>Error generating diff. Raw content:\n${escape(search)}\n========\n${escape(replace)}</pre>`;
    } finally {
      isLoading = false;
    }
  }

  function toggleDetails() {
    showDetails = !showDetails;
    if (showDetails) {
      generateDiff();
    }
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
            {#if isLoading}
                <div class="loading-diff">Loading diff...</div>
            {:else if diffHtml}
                {@html diffHtml}
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
        font-size: 0.85em;
    }
    .edit-block-body :global(pre) {
        margin: 0;
        /* Shiki adds a background color, which is fine. */
        /* It also adds horizontal padding, which we override on lines. */
        padding-top: 0.8em;
        padding-bottom: 0.8em;
        white-space: pre-wrap;
    }
    .edit-block-body :global(.diff-line) {
        display: block;
        padding-left: 0.8em;
        padding-right: 0.8em;
    }
    .edit-block-body :global(.diff-add) {
        background-color: var(--diff-add-bg);
    }
    .edit-block-body :global(.diff-del) {
        background-color: var(--diff-del-bg);
    }
    .loading-diff {
        padding: 1em;
        color: var(--chat-text);
        opacity: 0.7;
    }
</style>
