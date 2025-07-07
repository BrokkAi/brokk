<script lang="ts">
    import Markdown from 'svelte-exmarkdown';
    import remarkBreaks from 'remark-breaks';
    import {gfmPlugin} from 'svelte-exmarkdown/gfm';
    import {fade} from 'svelte/transition';
    import type {Bubble} from '../types';
    import Icon from "@iconify/svelte";
    import type {Plugin} from 'svelte-exmarkdown';
    import { derived } from 'svelte/store';
    import { ensureAndTrack, loadedLangs } from '../languageStore';
    import CopyablePre from './CopyablePre.svelte';

    export let bubble: Bubble;
    export let shikiPlugin: Plugin;

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

    // Languages that are needed for this bubble's markdown
    $: neededLangs = Array.from(
      bubble.markdown.matchAll(/```(\w+)/g),
      m => m[1].toLowerCase()
    ).filter(lang => lang && lang !== 'text' && lang !== 'plaintext');

    // As soon as we know which languages are needed, start loading them.
    // This is a fire-and-forget operation.
    $: neededLangs.forEach(ensureAndTrack);

    // This derived store becomes true once all needed languages for this bubble are loaded.
    const allLangsLoaded = derived(loadedLangs, $loadedLangs =>
      neededLangs.every(lang => $loadedLangs.has(lang))
    );

    // The plugins array is reactive. It includes the shikiPlugin only when it's
    // available AND all required languages for this bubble have been loaded.
    $: plugins = [
        gfmPlugin(),
        { remarkPlugin: [remarkBreaks] },
        ...(shikiPlugin && $allLangsLoaded ? [shikiPlugin] : []),
        { renderer: { pre: CopyablePre } }
    ];
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
        <Markdown class="bubble" md={bubble.markdown} {plugins} />
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

    .spacer {
        flex: 1;
    }

    .copy-btn {
        background: transparent;
        border: none;
        cursor: pointer;
        color: var(--chat-text);
        opacity: 0.6;
        padding: 0.2em;
        transition: opacity 0.2s;
    }

    .copy-btn:hover {
        opacity: 1;
    }

    .copy-btn:focus {
        outline: none;
        opacity: 1;
    }
</style>
