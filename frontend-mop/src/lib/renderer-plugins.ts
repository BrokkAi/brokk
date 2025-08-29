import type {Plugin} from 'svelte-exmarkdown';
import CopyablePre from '../components/CopyablePre.svelte';
import EditBlock from '../components/EditBlock.svelte';
import SymbolAwareCode from '../components/SymbolAwareCode.svelte';

export const rendererPlugins: Plugin[] = [
    {renderer: {pre: CopyablePre, 'edit-block': EditBlock, code: SymbolAwareCode}} as Plugin,
];
