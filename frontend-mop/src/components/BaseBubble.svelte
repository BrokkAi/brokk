<script lang="ts">
    import HastRenderer from './HastRenderer.svelte';
    import { rendererPlugins } from '../lib/renderer-plugins';

    // Generic bubble; only needs .hast for rendering
    export let bubble: any;

    // CSS variable names for highlight and background (e.g., '--message-border-ai')
    export let hlVar: string;
    export let bgVar: string;

    // When true, hides the message body
    export let collapsed: boolean = false;

    // Optional handler for symbol-clicks (mousedown). Used by MessageBubble only.
    export let onMouseDown: ((e: MouseEvent) => void) | undefined;

    // Whether to prevent the browser context menu on right-click. Used by MessageBubble.
    export let preventContextMenu: boolean = false;
</script>

<div class="message-wrapper">
    <header class="header">
        <slot name="header"></slot>
    </header>

    {#if !collapsed}
        <div
            class="message-bubble"
            style="
                background-color: var({bgVar});
                border-left: 4px solid var({hlVar});
                color: var(--chat-text);
            "
            on:mousedown={onMouseDown}
            on:contextmenu={preventContextMenu ? (e) => e.preventDefault() : undefined}
        >
            {#if bubble?.hast}
                <HastRenderer tree={bubble.hast} plugins={rendererPlugins} />
            {/if}
        </div>
    {/if}
</div>

<style>
    .message-wrapper {
        display: flex;
        flex-direction: column;
        gap: 0.3em;
        width: 100%;
        margin-bottom: 1em;
    }

    .message-bubble {
        border-radius: 0.9em;
        padding: 0.8em 1.0em;
        display: flex;
        flex-direction: column;
        gap: 0.4em;
        word-break: break-word;
    }

    /* High contrast mode: add a subtle dotted border around the entire bubble */
    :global(.theme-high-contrast) .message-bubble {
        border: 1px dotted rgba(230, 230, 230, 0.3);
        border-left: 4px solid var(--border-color-hex); /* Preserve the accent border */
    }

    .header {
        display: flex;
        align-items: center;
        font-weight: 600;
        font-size: 0.95em;
    }
</style>
