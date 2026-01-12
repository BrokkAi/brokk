<script lang="ts">
  import { staticDocStore } from '../stores/staticDocStore';
  import HastRenderer from './HastRenderer.svelte';
  import { rendererPlugins } from '../lib/renderer-plugins';
  import { zoomStore } from '../stores/zoomStore';
</script>

{#if $staticDocStore?.tree}
  <div class="static-document" style="--zoom-level: {$zoomStore}">
    <HastRenderer tree={$staticDocStore.tree} plugins={rendererPlugins} />
  </div>
{/if}

<style>
  .static-document {
    padding: 2rem;
    color: var(--chat-text);
    line-height: 1.5;
    font-size: calc(14px * var(--zoom-level, 1));
    overflow-y: auto;
    height: 100%;
  }
</style>
