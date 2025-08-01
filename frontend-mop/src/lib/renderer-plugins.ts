import type {Plugin} from 'svelte-exmarkdown';
import CopyablePre from '../components/CopyablePre.svelte';
import EditBlock from '../components/EditBlock.svelte';

export const rendererPlugins: Plugin[] = [
    {renderer: {pre: CopyablePre, 'edit-block': EditBlock}} as Plugin,
];
