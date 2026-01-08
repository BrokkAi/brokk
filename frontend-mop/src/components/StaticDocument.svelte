<script lang="ts">
  import { staticDocStore } from '../stores/staticDocStore';
  import HastRenderer from './HastRenderer.svelte';
  import { rendererPlugins } from '../lib/renderer-plugins';
</script>

{#if $staticDocStore?.tree}
  <div class="static-document">
    <HastRenderer tree={$staticDocStore.tree} plugins={rendererPlugins} />
  </div>
{/if}

<style>
  .static-document {
    padding: 2rem;
    color: var(--chat-text);
    line-height: 1.5;
    font-size: 14px;
    overflow-y: auto;
    height: 100%;
  }
</style>
