<script lang="ts">
    import { Renderer } from 'svelte-exmarkdown';
    import { ref, setComponentsContext } from 'svelte-exmarkdown/contexts';
    import type { HastNode, ComponentsMap, Plugin } from 'svelte-exmarkdown/types';
    import { getComponentsFromPlugins } from 'svelte-exmarkdown/utils';

  export let tree: HastNode;
  export let plugins: Plugin[] = [];
  export let components: ComponentsMap = {};

  // Merge components from plugins and direct components prop
  const allPlugins = [...plugins, { renderer: components }];
  const componentsMap = getComponentsFromPlugins(allPlugins);

  const componentsContextValue = ref(componentsMap);
  setComponentsContext(componentsContextValue);
</script>

<Renderer astNode={tree} />
