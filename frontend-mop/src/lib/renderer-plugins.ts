import type {Plugin} from 'svelte-exmarkdown';
import CopyablePre from '../components/CopyablePre.svelte';
import EditBlock from '../components/EditBlock.svelte';
import ContentAwareCode from '../components/ContentAwareCode.svelte';

export const rendererPlugins: Plugin[] = [
    {renderer: {pre: CopyablePre, 'edit-block': EditBlock, code: ContentAwareCode}} as Plugin,
];
