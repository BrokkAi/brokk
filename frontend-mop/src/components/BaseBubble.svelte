<script lang="ts">
    import HastRenderer from './HastRenderer.svelte';
    import { rendererPlugins } from '../lib/renderer-plugins';

    let {
        bubble,
        hlVar,
        bgVar,
        collapsed = false,
        onMouseDown,
        preventContextMenu = false,
    } = $props<{
        bubble: any;
        hlVar: string;
        bgVar: string;
        collapsed?: boolean;
        onMouseDown?: ((e: MouseEvent) => void) | undefined;
        preventContextMenu?: boolean;
    }>();

    let terminalPre: HTMLPreElement | null = null;

    function isNearBottom(elem: HTMLElement, thresholdPx: number): boolean {
        const remaining = elem.scrollHeight - elem.scrollTop - elem.clientHeight;
        return remaining <= thresholdPx;
    }

    function scrollToBottom(): void {
        if (!terminalPre) return;
        terminalPre.scrollTop = terminalPre.scrollHeight;
    }

    $effect(() => {
        if (!bubble?.isTerminal) return;
        if (!bubble?.streaming) return;
        if (!terminalPre) return;

        if (!isNearBottom(terminalPre, 24)) return;

        queueMicrotask(scrollToBottom);
    });
</script>

<div class="message-wrapper">
    <header class="header">
        <slot name="header"></slot>
    </header>

    {#if !collapsed}
        <div
            class="message-bubble"
            class:terminal-bubble={bubble?.isTerminal}
            style="
                background-color: var({bgVar});
                border-left: 4px solid var({hlVar});
                color: var(--chat-text);
            "
            on:mousedown={onMouseDown}
            on:contextmenu={preventContextMenu ? (e) => e.preventDefault() : undefined}
        >
            {#if bubble?.isTerminal}
                <pre class="terminal-pre" bind:this={terminalPre}>{bubble?.markdown ?? ''}</pre>
            {:else if bubble?.hast}
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

    .terminal-bubble {
        padding: 0.6em 0.7em;
    }

    .terminal-pre {
        margin: 0;
        padding: 12px 14px;
        border-radius: 10px;

        background-color: var(--shiki-background, #0b0f14);
        color: var(--shiki-foreground, #e6edf3);

        font-family: "Fira Code Retina", monospace;
        font-size: 0.9em;
        line-height: 1.45;

        white-space: pre;
        overflow: auto;
        max-height: var(--terminal-max-height, min(60vh, 520px));
        scrollbar-width: thin;
    }

    :global(.theme-high-contrast) .terminal-pre {
        background-color: #000000;
        color: #ffffff;
    }

    .header {
        display: flex;
        align-items: center;
        font-weight: 600;
        font-size: 0.95em;
    }
</style>
