<script lang="ts">
  import Icon from "@iconify/svelte";
  import { envStore } from "../stores/envStore";

  type Suggestion = {
    icon: string;
    title: string;
    desc: string;
  };

  const allSuggestions: Suggestion[] = [
    {
      icon: "mdi:playlist-check",
      title: 'Use Lutz Mode',
      desc: "Brokk will search for relevant context, then break down your instructions into tasks.",
    },
    {
      icon: "mdi:content-paste",
      title: "Paste errors, exceptions, images, URLs, or code snippets",
      desc: "Adding context in the workspace yields better, faster answers.",
    },
  ];

  // Filter out Lutz Mode suggestion when simplified instructions panel is enabled
  $: suggestions = $envStore.isSimplifiedInstructionsPanel
    ? allSuggestions.filter(s => s.title !== 'Use Lutz Mode')
    : allSuggestions;

  function pluralize(n: number, singular: string, plural?: string): string {
    return n === 1 ? singular : (plural ?? `${singular}s`);
  }
</script>

<div class="empty-state">
  <div class="empty-icon">&#123; &#125;</div>
  <h2>Start a new conversation to begin coding with Brokk</h2>
  <p>Ask questions, request code reviews, or describe what you'd like to build</p>

  <div class="suggestions">
    {#each suggestions as s}
      <div class="suggestion">
        <Icon icon={s.icon} class="suggestion-icon" />
        <div>
          <div class="suggestion-title">{s.title}</div>
          <div class="suggestion-desc">{s.desc}</div>
        </div>
      </div>
    {/each}
  </div>

  <div class="env-section">
    <div class="env-title">
      <Icon icon="mdi:information-outline" class="env-icon" />
      <span>Environment</span>
    </div>

    <div class="env-row">
      <div class="env-label">Brokk version</div>
      <div class="env-value">{$envStore.version ?? 'unknown'}</div>
    </div>

    <div class="env-row">
      <div class="env-label">Project</div>
      <div class="env-value">
        {$envStore.projectName ?? 'unknown'}
        {#if $envStore.analyzerLanguages && $envStore.analyzerLanguages.length > 0}
          {@const analyzedCount = $envStore.analyzerLanguages.reduce((sum, lang) => sum + lang.fileCount, 0)}
          {@const unanalyzedCount = ($envStore.totalFileCount ?? 0) - analyzedCount}
          <span class="env-muted">(analyzed: {analyzedCount}, unanalyzed: {unanalyzedCount})</span>
        {:else if $envStore.totalFileCount !== undefined}
          <span class="env-muted">(total files: {$envStore.totalFileCount})</span>
        {/if}
      </div>
    </div>

    <div class="env-row analyzer-row">
      <div class="env-label">Analyzers</div>
      <div class="env-value">
        {#if $envStore.analyzerReady}
          <span class="env-badge ready">Ready</span>
        {:else}
          <span class="env-badge progress">Building...</span>
        {/if}
        {#if $envStore.analyzerLanguages && $envStore.analyzerLanguages.length > 0}
          <div class="languages-list">
            {#each $envStore.analyzerLanguages as lang}
              <div class="language-item">
                {lang.name} ({lang.fileCount} {pluralize(lang.fileCount, 'file', 'files')}, {lang.depCount} {pluralize(lang.depCount, 'dep', 'deps')})
              </div>
            {/each}
          </div>
        {/if}
      </div>
    </div>
  </div>
</div>

<style>
  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    padding: 2rem;
    text-align: center;
    color: var(--chat-text);
  }

  .empty-icon {
    font-size: 4rem;
    margin-bottom: 1.5rem;
    color: var(--badge-foreground);
    font-family: monospace;
    font-weight: 300;
  }

  h2 {
    font-size: 1.5rem;
    font-weight: 600;
    margin: 0 0 0.5rem 0;
    color: var(--chat-text);
  }

  p {
    font-size: 1.1rem;
    margin: 0 0 2rem 0;
    color: var(--chat-text);
    opacity: 0.8;
  }

  .suggestions {
    display: flex;
    flex-direction: column;
    gap: 1rem;
    width: 100%;
    max-width: 600px;
  }

  .suggestion {
    display: flex;
    align-items: center;
    gap: 1rem;
    padding: 1rem 1.5rem;
    background: var(--message-background);
    border: 1px solid var(--border-color-hex);
    border-radius: 0.8rem;
    text-align: left;
    transition: background-color 0.2s ease;
  }

  .suggestion:hover {
    background: color-mix(in srgb, var(--chat-background) 50%, var(--message-background));
  }

  .suggestion-icon {
    font-size: 2rem;
    flex-shrink: 0;
    color: var(--chat-text);
  }

  .suggestion-title {
    font-weight: 600;
    margin-bottom: 0.25rem;
    color: var(--chat-text);
  }

  .suggestion-desc {
    font-size: 0.9rem;
    color: var(--chat-text);
    opacity: 0.8;
  }

  /* Environment info styles */
  .env-section {
    margin-top: 2rem;
    width: 100%;
    max-width: 550px;
    text-align: left;
    background: var(--message-background);
    border: 1px solid var(--badge-border);
    border-radius: 0.8rem;
    padding: 1rem 1.5rem;
  }

  .env-title {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-weight: 600;
    color: var(--chat-text);
    margin-bottom: 0.5rem;
  }

  .env-icon {
    font-size: 1.25rem;
    color: var(--chat-text);
    opacity: 0.9;
  }

  .env-row {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    padding: 0.4rem 0;
    border-top: 1px dashed var(--border-color-hex);
  }

  .env-row:first-of-type {
    border-top: none;
  }

  .env-label {
    font-size: 0.9rem;
    opacity: 0.8;
  }

  .env-value {
    font-size: 0.95rem;
    color: var(--chat-text);
  }

  .env-muted {
    opacity: 0.7;
  }

  .env-badge {
    display: inline-block;
    padding: 0.1rem 0.5rem;
    border-radius: 0.5rem;
    font-size: 0.85rem;
    border: 1px solid var(--border-color-hex);
    background: var(--chat-background);
  }

  .env-badge.ready {
    color: var(--diff-add);
    border-color: var(--diff-add);
    background: var(--diff-add-bg);
    font-weight: 600;
  }

  .env-badge.progress {
    color: var(--git-changed);
    border-color: var(--git-changed);
    background: color-mix(in srgb, var(--git-changed) 15%, transparent);
    font-weight: 600;
  }

  .analyzer-row .env-value {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 0.5rem;
  }

  .languages-list {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    margin-top: 0.25rem;
    padding-left: 1.5rem;
  }

  .language-item {
    font-size: 0.9rem;
    color: var(--chat-text);
    opacity: 0.9;
  }
</style>
