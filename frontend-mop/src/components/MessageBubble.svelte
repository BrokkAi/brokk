<script lang="ts">
  import Markdown from 'svelte-exmarkdown';
  import remarkBreaks from 'remark-breaks';
  import { gfmPlugin } from 'svelte-exmarkdown/gfm';
  import { fade } from 'svelte/transition';
  import type { Bubble } from '../types';

  export let bubble: Bubble;

  $: bubbleClass = `bubble ${bubble.type}`;
</script>

<div class={bubbleClass} in:fade={{ duration: 150 }} out:fade={{ duration: 150 }}>
  <Markdown md={bubble.markdown} plugins={[gfmPlugin(), remarkBreaks()]} />
</div>

<style>
  .bubble {
    padding: 0.8em 1.2em;
    border-radius: 0.8em;
    white-space: pre-wrap;
    word-break: break-word;
    display: inline-block;
    width: 100%;
  }
  .bubble.USER {
    background-color: #e3f2fd;
    align-self: flex-start;
  }
  .bubble.AI {
    background-color: #f5f5f5;
    align-self: flex-end;
  }
  .bubble.SYSTEM {
    background-color: #fff3e0;
    align-self: center;
    text-align: center;
  }
  .theme-dark .bubble.USER {
    background-color: #1e3a8a;
  }
  .theme-dark .bubble.AI {
    background-color: #2e7d32;
  }
  .theme-dark .bubble.SYSTEM {
    background-color: #9a3412;
  }
</style>
