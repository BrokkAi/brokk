
<script lang="ts">
    import CollapsibleEditPanel from './CollapsibleEditPanel.svelte';
    import { expandDiff } from '../worker/worker-bridge';
    import type { LineEditBlockProperties } from '../worker/shared';
    import { bubblesStore } from '../stores/bubblesStore';
    import { get } from 'svelte/store';

    let {
        id = '-1',
        filename = '?',
        adds = 0,
        dels = 0,
        headerOk = false,
        isExpanded = false,
        bubbleId,
        op,          // accepted but not used directly in the header shell
        beginline,   // accepted for future use
        endline,     // accepted for future use
        body,        // accepted for future use
    } = $props<LineEditBlockProperties>();

    function onExpand(_payload: { id: string; bubbleId: number }) {
        const markdown = get(bubblesStore).find(b => b.seq === bubbleId)?.markdown ?? '';
        // Reuse the existing expand-diff message; worker-side transformer will render body on demand
        expandDiff(markdown, bubbleId, id);
    }
</script>

<CollapsibleEditPanel
    {id}
    {filename}
    {adds}
    {dels}
    {headerOk}
    {isExpanded}
    {bubbleId}
    expand={onExpand}
>
    <slot></slot>
</CollapsibleEditPanel>
