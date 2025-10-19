<script lang="ts">
  import Icon from "@iconify/svelte";
  import { envStore } from "../stores/envStore";

  type Suggestion = {
    icon: string;
    title: string;
    desc: string;
  };

  const suggestions: Suggestion[] = [
    {
      icon: "mdi:playlist-check",
      title: 'Use Code mode with "Plan First" enabled',
      desc: "Let Brokk outline a plan, then implement step-by-step (agentic coding).",
    },
    {
      icon: "mdi:magnify-scan",
      title: 'Use Answer mode with "Search first"',
      desc: "Searches your codebase to ground answers in your project.",
    },
    {
      icon: "mdi:content-paste",
      title: "Paste errors, exceptions, images, URLs, or code snippets",
      desc: "Adding context in the workspace yields better, faster answers.",
    },
    {
      icon: "mdi:file-plus-outline",
      title: "Add files as editable, read-only, or summaries",
      desc: "Attach from the file tree or accept blue-badge suggestions.",
    },
  ];

  function formatLanguages(langs?: string | string[]): string | null {
    if (!langs) return null;
    return Array.isArray(langs) ? langs.join(", ") : langs;
  }

  function pluralize(n: number, singular: string, plural?: string): string {
    return n === 1 ? singular : (plural ?? `${singular}s`);
  }

  let depCount: number | undefined;
  $: depCount =
    $envStore.nativeFileCount !== undefined && $envStore.totalFileCount !== undefined
      ? Math.max($envStore.totalFileCount - $envStore.nativeFileCount, 0)
      : undefined;
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
        {#if $envStore.nativeFileCount !== undefined || $envStore.totalFileCount !== undefined}
          <span class="env-muted">
            (
            {#if $envStore.nativeFileCount !== undefined && $envStore.totalFileCount !== undefined}
              {$envStore.nativeFileCount} {pluralize($envStore.nativeFileCount, 'file', 'files')}, {depCount} {pluralize(depCount ?? 0, 'dep', 'deps')}
            {:else if $envStore.nativeFileCount !== undefined}
              {$envStore.nativeFileCount} {pluralize($envStore.nativeFileCount, 'file', 'files')}
            {:else}
              total files with deps
            {/if}
            )
          </span>
        {/if}
      </div>
    </div>

    <div class="env-row">
      <div class="env-label">Analyzer</div>
      <div class="env-value">
        {#if $envStore.analyzerReady}
          <span class="env-badge ready">Ready</span>
          {#if formatLanguages($envStore.analyzerLanguages)}
            <span class="env-muted"> — {formatLanguages($envStore.analyzerLanguages)}</span>
          {/if}
        {:else}
          <span class="env-badge progress">Building...</span>
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
</style>
