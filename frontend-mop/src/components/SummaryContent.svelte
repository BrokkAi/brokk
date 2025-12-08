<script lang="ts">
  import HastRenderer from './HastRenderer.svelte';
  import { rendererPlugins } from '../lib/renderer-plugins';

  export let tree: any | undefined;
  export let textFallback: string = '';
</script>

<div class="summary-renderer">
  {#if tree}
    <HastRenderer tree={tree} plugins={rendererPlugins} />
  {:else}
    <p>{textFallback}</p>
  {/if}
</div>

<style>
  /* Summary renderer styling */
  .summary-renderer {
    padding: 1em;
    background-color: var(--system-background, var(--message-background));
    border-radius: 0.9em;
    border-left: 4px solid var(--summary-border-color, #9b59b6);
    color: var(--system-text, var(--chat-text));
  }
</style>
