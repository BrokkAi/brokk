<script lang="ts">
  import Icon from "@iconify/svelte";
  import CodeBlockShell from "./CodeBlockShell.svelte";
  import type { BubbleState } from "../types";

  let { bubble } = $props<{
    bubble: BubbleState;
  }>();

  let preElem: HTMLPreElement | null = null;
  let codeElem: HTMLElement | null = null;

  let renderedLength = 0;

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

  $effect(() => {
    if (!preElem || !codeElem) return;

    const text = bubble?.markdown ?? "";

    if (text.length > renderedLength) {
      // Append only the new portion (delta)
      const delta = text.slice(renderedLength);
      codeElem.appendChild(document.createTextNode(delta));
      renderedLength = text.length;

      if (shouldAutoScroll) {
        queueMicrotask(() => {
          if (preElem) scrollToBottom();
        });
      }
    } else if (text.length < renderedLength) {
      // Content was cleared or replaced - full re-render
      codeElem.textContent = text;
      renderedLength = text.length;
    }
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
    {#if bubble.streaming}
      <div class="terminal-status">
        <Icon icon="mdi:loading" class="spin-icon" />
      </div>
    {/if}
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

    white-space: nowrap;
    overflow: auto;
    max-height: var(--terminal-max-height, min(60vh, 520px));
    scrollbar-width: thin;
  }

  .terminal {
    white-space: pre;
    display: inline;
  }

  .terminal-status {
    position: absolute;
    bottom: 8px;
    right: 12px;
    display: flex;
    align-items: center;
    color: var(--shiki-foreground);
    opacity: 0.7;
    pointer-events: none;
  }

  :global(.spin-icon) {
    animation: spin 1.5s linear infinite;
  }

  @keyframes spin {
    from {
      transform: rotate(0deg);
    }
    to {
      transform: rotate(360deg);
    }
  }
</style>
