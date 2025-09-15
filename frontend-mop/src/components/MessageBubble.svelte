<script lang="ts">
  import Icon from "@iconify/svelte";
  import HastRenderer from './HastRenderer.svelte';
  import { rendererPlugins } from '../lib/renderer-plugins';
  import type { BubbleState } from '../stores/bubblesStore';
  import type {Bubble} from "@/types";
  import { getBubbleDisplayDefaults } from '../lib/bubble-utils';
  import { createLogger } from '../lib/logging';

  export let bubble: Bubble;

  const log = createLogger('symbol-click');


  function handleSymbolClick(event: MouseEvent) {
    const target = event.target as HTMLElement;
    if (target.tagName === 'CODE' && target.classList.contains('symbol-exists')) {
      const symbolName = target.getAttribute('data-symbol');
      const symbolExists = target.getAttribute('data-symbol-exists') === 'true';
      const symbolFqn = target.getAttribute('data-symbol-fqn');

      if (event.button === 2) { // Right click
        event.preventDefault();
        log.info(`Right-clicked symbol: ${symbolName}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}`);

        // Call Java bridge for right-click with coordinates
        if (window.javaBridge && window.javaBridge.onSymbolClick) {
          window.javaBridge.onSymbolClick(symbolName, symbolExists, symbolFqn, event.clientX, event.clientY);
        }
      } else if (event.button === 0) { // Left click
        log.info(`Left-clicked symbol: ${symbolName}, exists: ${symbolExists}`);
        // Left click behavior can be added here later
      }
    }
  }

  const defaults = getBubbleDisplayDefaults(bubble.type);
  const hlVar = defaults.hlVar;
  const bgVar = defaults.bgVar;

  /* Use provided title/icon if available, otherwise fall back to defaults */
  const title = bubble.title ?? defaults.title;
  const iconId = bubble.iconId ?? defaults.iconId;
</script>

<div
  class="message-wrapper"
>
  <header class="header" style="color: var({hlVar});">
      <Icon icon={iconId} style="color: var({hlVar}); margin-right: 0.35em;" />
      <span class="title">{title}</span>
  </header>
  <div
    class="message-bubble"
    style="
      background-color: var({bgVar});
      border-left: 4px solid var({hlVar});
      color: var(--chat-text);
    "
    on:mousedown={handleSymbolClick}
    on:contextmenu={(e) => e.preventDefault()}
  >
    {#if bubble.hast}
      <HastRenderer tree={bubble.hast} plugins={rendererPlugins} />
    {/if}
  </div>
</div>


<style>
  .message-wrapper {
    display: flex;
    flex-direction: column;
    gap: 0.3em;
    width: 100%;
  }

  .message-bubble {
    border-radius: 0.9em;
    padding: 0.8em 1.1em;
    display: flex;
    flex-direction: column;
    gap: 0.4em;
    word-break: break-word;
  }

  .header {
    display: flex;
    align-items: center;
    font-weight: 600;
    font-size: 0.95rem;
  }

</style>
