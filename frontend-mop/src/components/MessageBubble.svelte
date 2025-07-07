<script lang="ts">
    import Markdown from 'svelte-exmarkdown';
    import remarkBreaks from 'remark-breaks';
    import {gfmPlugin} from 'svelte-exmarkdown/gfm';
    import {fade} from 'svelte/transition';
    import type {Bubble} from '../types';
    import Icon from "@iconify/svelte";
    import type {Plugin} from 'svelte-exmarkdown';
    import {ensureLang} from '../shiki-plugin';

    export let bubble: Bubble;
    export let shikiPlugin: Plugin | null = null;

    /* Map bubble type to CSS variable names for highlight and background colors */
    const hlVar = {
        AI: '--message-border-ai',
        USER: '--message-border-user',
        CUSTOM: '--message-border-custom',
        SYSTEM: '--message-border-custom'
    }[bubble.type] ?? '--message-border-custom';

    const bgVar = bubble.type === 'CUSTOM' ? '--custom-message-background' : '--message-background';

    /* Default titles and icons per bubble type */
    const defaultTitles = {USER: 'You', AI: 'Brokk', SYSTEM: 'System', CUSTOM: 'Custom'};
    const defaultIcons = {USER: 'mdi:account', AI: 'mdi:robot', SYSTEM: 'mdi:cog', CUSTOM: 'mdi:wrench'};

    /* Use provided title/icon if available, otherwise fall back to defaults */
    $: title = bubble.title ?? defaultTitles[bubble.type] ?? 'Message';
    $: iconId = bubble.iconId ?? defaultIcons[bubble.type] ?? 'mdi:message';

    // Dynamically load languages if needed based on markdown content
    $: {
        if (shikiPlugin) {
            // Extract code block languages from markdown
            const codeBlocks = bubble.markdown.matchAll(/```(\w+)/g);
            for (const match of codeBlocks) {
                const lang = match[1].toLowerCase();
                if (lang && lang !== 'text' && lang !== 'plaintext') {
                    ensureLang(lang);
                }
            }
        }
    }

    $: plugins = shikiPlugin ? [gfmPlugin(), remarkBreaks(), shikiPlugin] : [gfmPlugin(), remarkBreaks()];
</script>

<div
        class="message-wrapper"
        in:fade={{ duration: 150 }}
        out:fade={{ duration: 150 }}
>
    <header class="header" style="color: var({hlVar});">
        <Icon icon={iconId} style="color: var({hlVar}); margin-right: 0.35em;"/>
        <span class="title">{title}</span>
    </header>
    <div
            class="message-bubble"
            style="
      background-color: var({bgVar});
      border-left: 4px solid var({hlVar});
      color: var(--chat-text);
    "
    >
        <Markdown class="bubble" md={bubble.markdown} {plugins}>
            {#snippet pre(props)}
                {@const {children, ...rest} = props }
                {@const lang = props['data-language'] || 'text'}
                <div class="custom-code-block">
                    <div class="custom-code-header">
                        <Icon icon="mdi:code-braces" />
                        <span class="language-name">{lang}</span>
                    </div>
                    <pre {...rest}>{@render children?.()}</pre>
                </div>
            {/snippet}
        </Markdown>
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

    .code-block {
        border-radius: 8px;
        overflow: hidden;
        margin: 0.75em 0;
    }

    .code-header {
        display: flex;
        align-items: center;
        gap: 0.35em;
        padding: 0.3em 0.6em;
        font-size: 0.8rem;
        font-weight: 600;
        background: var(--code-block-background);
        border-left: 4px solid var(--code-block-border);
    }

    .language-name {
        text-transform: uppercase;
        opacity: 0.7;
    }
</style>
