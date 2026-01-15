<script lang="ts">
  import CodeBlockShell from "./CodeBlockShell.svelte";
  import type { BubbleState } from "../types";

  let { bubble } = $props<{
    bubble: BubbleState;
  }>();

  // Svelte 5: bind:this targets should be reactive so effects re-run when the DOM node is bound/unbound.
  let preElem: HTMLPreElement | null = $state(null);
  let codeElem: HTMLElement | null = $state(null);

  // Tracks how much of bubble.markdown has been appended to the <code> node.
  // Kept as plain instance-local state (not template state).
  let renderedLength = 0;

  // Auto-scroll only while the user is near the bottom, so manual scrollback is not disrupted.
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
    const text = bubble?.markdown ?? "";

    if (!preElem || !codeElem) return;

    if (text.length > renderedLength) {
      // Streaming case: append only the delta to avoid re-rendering large terminal output.
      const delta = text.slice(renderedLength);
      codeElem.appendChild(document.createTextNode(delta));
      renderedLength = text.length;

      if (shouldAutoScroll) {
        // Defer until after layout so scrollHeight reflects the appended text.
        queueMicrotask(() => {
          if (preElem) scrollToBottom();
        });
      }
    } else if (text.length < renderedLength) {
      // Reset case: content was cleared/replaced, so rebuild the full text.
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
      <code class="terminal" bind:this={codeElem}></code>{#if !bubble.terminalComplete}<span
          class="terminal-cursor">█</span
        >{/if}
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

    white-space: nowrap;
    overflow: auto;
    max-height: var(--terminal-max-height, min(60vh, 520px));
    scrollbar-width: thin;
  }

  .terminal {
    white-space: pre;
    display: inline;
  }

  .terminal-cursor {
    display: inline;
    animation: blink 1s step-end infinite;
  }

  @keyframes blink {
    0%,
    50% {
      opacity: 1;
    }
    51%,
    100% {
      opacity: 0;
    }
  }
</style>
