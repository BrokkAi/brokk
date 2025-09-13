<script lang="ts">
  import Icon from "@iconify/svelte";

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
    color: var(--border-color-hex);
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
</style>
