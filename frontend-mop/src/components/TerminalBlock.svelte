<script lang="ts">
  import CodeBlockShell from "./CodeBlockShell.svelte";

  let { terminalChunks = [] } = $props<{
    terminalChunks: string[];
  }>();

  let preElem: HTMLPreElement | null = null;
  let codeElem: HTMLElement | null = null;

  let appendedCount = 0;

  let shouldAutoScroll = true;
  const bottomThresholdPx = 24;

  function isNearBottom(elem: HTMLElement): boolean {
    const remaining = elem.scrollHeight - elem.scrollTop - elem.clientHeight;
    return remaining <= bottomThresholdPx;
  }

  function scrollToBottom(): void {
    if (!preElem) return;
    preElem.scrollTop = preElem.scrollHeight;
  }

  function appendNewChunks(): void {
    if (!codeElem) return;

    const next = terminalChunks ?? [];
    if (next.length <= appendedCount) return;

    for (let i = appendedCount; i < next.length; i++) {
      codeElem.appendChild(document.createTextNode(next[i] ?? ""));
    }
    appendedCount = next.length;

    if (shouldAutoScroll) {
      queueMicrotask(() => {
        if (preElem) scrollToBottom();
      });
    }
  }

  $effect(() => {
    if (!preElem || !codeElem) return;

    if (terminalChunks.length < appendedCount) {
      codeElem.textContent = "";
      appendedCount = 0;
    }

    appendNewChunks();
  });

  function onScroll(): void {
    if (!preElem) return;
    shouldAutoScroll = isNearBottom(preElem);
  }
</script>

<CodeBlockShell languageLabel="Terminal">
  <svelte:fragment slot="default" let:preId let:registerPre let:handleWheel>
    <pre
      id={preId}
      class="terminal-pre"
      use:registerPre
      bind:this={preElem}
      on:scroll={onScroll}
      on:wheel|passive={handleWheel}
    >
      <code class="terminal" bind:this={codeElem}></code>
    </pre>
  </svelte:fragment>
</CodeBlockShell>

<style>
  .terminal-pre {
    padding: 12px 14px;
    background-color: var(--shiki-background);
    color: var(--shiki-foreground);

    font-family: "Fira Code Retina", monospace;
    font-size: 0.9em;
    line-height: 1.45;

    white-space: pre;
    overflow: auto;
    max-height: var(--terminal-max-height, min(60vh, 520px));
    scrollbar-width: thin;
  }

  .terminal {
    white-space: pre;
    display: block;
  }

  :global(.theme-high-contrast) .terminal-pre {
    background-color: #000000;
    color: #ffffff;
  }
</style>
