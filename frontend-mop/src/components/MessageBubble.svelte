<script lang="ts">
    import Icon from "@iconify/svelte";
    import BaseBubble from './BaseBubble.svelte';
    import type {Bubble} from "@/types";
    import {getBubbleDisplayDefaults} from '../lib/bubble-utils';
    import {createLogger} from '../lib/logging';

    export let bubble: Bubble;

    const log = createLogger('symbol-click');

    function handleSymbolClick(event: MouseEvent) {
        const target = event.target as HTMLElement;
        if (target.tagName === 'CODE' && target.classList.contains('symbol-exists')) {
            const symbolName = target.getAttribute('data-symbol');
            const symbolExists = target.getAttribute('data-symbol-exists') === 'true';
            const symbolFqn = target.getAttribute('data-symbol-fqn');

            if (event.button === 2) {
                event.preventDefault();
                log.info(`Right-clicked symbol: ${symbolName}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}`);

                if (window.javaBridge && window.javaBridge.onSymbolClick) {
                    window.javaBridge.onSymbolClick(symbolName, symbolExists, symbolFqn, event.clientX, event.clientY);
                }
            } else if (event.button === 0) {
                log.info(`Left-clicked symbol: ${symbolName}, exists: ${symbolExists}`);
            }
        }
    }

    $: defaults = getBubbleDisplayDefaults(bubble.type);
    $: hlVar = defaults.hlVar;
    $: bgVar = defaults.bgVar;

    // Use provided title/icon if available, otherwise fall back to defaults
    $: title = bubble.title ?? defaults.title;
    $: iconId = bubble.iconId ?? defaults.iconId;
</script>

<BaseBubble {bubble} {hlVar} {bgVar} onMouseDown={handleSymbolClick} preventContextMenu={true}>
    <div slot="header" class="header-content" style="color: var({hlVar});">
        <Icon icon={iconId} style="margin-right: 0.35em;" />
        <span class="title">{title}</span>
    </div>
</BaseBubble>

<style>
/* Shared layout and bubble styles are provided by BaseBubble */
</style>
