
<script lang="ts">
    import CollapsibleEditPanel from './CollapsibleEditPanel.svelte';
    import { expandDiff } from '../worker/worker-bridge';
    import type { EditBlockProperties } from '../worker/shared';
    import { bubblesStore } from '../stores/bubblesStore';
    import { get } from 'svelte/store';

    let {
        id = '-1',
        filename = '?',
        adds = 0,
        dels = 0,
        search = '',
        replace = '',
        headerOk = false,
        isExpanded = false,
        bubbleId,
    } = $props<EditBlockProperties>();

    function onExpand(_payload: { id: string; bubbleId: number }) {
        const markdown = get(bubblesStore).find(b => b.seq === bubbleId)?.markdown ?? '';
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
