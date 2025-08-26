<script lang="ts">
  import {onMount} from 'svelte';
  import {symbolCacheStore, requestSymbolResolution, type SymbolCacheEntry} from '../stores/symbolCacheStore';
  import {createWorkerLogger} from '../lib/logging';

  let {children, ...rest} = $props();

  const log = createWorkerLogger('symbol-aware-code');

  // Extract symbol text from children
  let symbolText = $state('');
  let isValidSymbol = $state(false);
  let cacheEntry: SymbolCacheEntry | undefined = $state(undefined);
  let contextId = 'main-context'; // TODO: Get from context if needed

  // Unique identifier for this component instance
  const componentId = `symbol-${Math.random().toString(36).substr(2, 9)}`;

  // Java class name validation - matches the worker logic
  function cleanSymbolName(raw: string): string {
    const trimmed = raw.trim();

    if (trimmed.length < 2 || trimmed.length > 200) {
      return '';
    }

    return trimmed;
    /*
    if (isValidJavaClassName(trimmed)) {
      return trimmed;
    }

    return '';
    */
  }

  function isValidJavaClassName(name: string): boolean {
    const segments = name.split('.');

    for (const segment of segments) {
      if (!isValidJavaIdentifier(segment)) {
        return false;
      }
    }

    if (segments.length > 1) {
      const className = segments[segments.length - 1];
      return /^[A-Z]/.test(className);
    }

    return /^[A-Z]/.test(name);
  }

  function isValidJavaIdentifier(identifier: string): boolean {
    return /^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(identifier);
  }

  // Extract text from children - for inline code, this should be simple text
  function extractTextFromChildren(): string {
    // For svelte-exmarkdown, the children prop for inline code elements
    // should contain the text content directly accessible via props
    if (rest && 'children' in rest && Array.isArray(rest.children)) {
      // If children is in rest props as an array (text nodes)
      return rest.children.map(child =>
        typeof child === 'string' ? child :
        (child && typeof child === 'object' && 'value' in child) ? child.value : ''
      ).join('');
    }

    // In Svelte 5, children are snippets (functions) that return rendered content
    // We need to extract the text from the rendered result
    try {
      if (typeof children === 'function') {
        const result = children();

        log.debug('Children function result:', result, 'typeof:', typeof result);

        // If it's a string, return it directly
        if (typeof result === 'string') {
          return result;
        }

        // If it's an array of nodes (common in Svelte 5), extract text
        if (Array.isArray(result)) {
          return result.map(node => {
            if (typeof node === 'string') return node;
            if (node && typeof node === 'object' && 'data' in node) return node.data;
            return '';
          }).join('');
        }

        // If it's a text node object
        if (result && typeof result === 'object' && 'data' in result) {
          return result.data;
        }

        // Log unexpected result structure for debugging
        log.debug('Unexpected children result structure:', result);
      }
    } catch (e) {
      log.debug('Could not extract from children function:', e);
    }

    return '';
  }

  onMount(() => {
    // For svelte-exmarkdown inline code, the text content might be in different places
    // First try extracting from the children function
    symbolText = extractTextFromChildren();

    // If that didn't work, try getting from current element's textContent after mount
    if (!symbolText) {
      // We'll need to get this from the actual DOM element after render
      setTimeout(() => {
        const thisElement = document.querySelector(`code[data-symbol-id="${componentId}"]`);
        if (thisElement) {
          const textContent = thisElement.textContent?.trim() || '';
          log.debug(`DOM extraction for ${componentId} - textContent: "${textContent}", innerHTML: "${thisElement.innerHTML}"`);
          symbolText = textContent;
          validateAndRequestSymbol();
        }
      }, 0);
    } else {
      validateAndRequestSymbol();
    }
  });

  function validateAndRequestSymbol() {
    const cleaned = cleanSymbolName(symbolText);

    if (cleaned) {
      isValidSymbol = true;
      symbolText = cleaned;

      // Request symbol resolution
      requestSymbolResolution(symbolText, contextId);

      log.debug(`Symbol component mounted for '${symbolText}'`);
    } else {
      log.debug(`Invalid symbol text: '${symbolText}'`);
    }
  }

  // Subscribe to symbol cache updates
  $effect(() => {
    if (isValidSymbol) {
      const cacheKey = `${contextId}:${symbolText}`;
      cacheEntry = $symbolCacheStore.get(cacheKey);
    }
  });

  // Determine if symbol exists and get FQN using derived state
  let symbolExists = $derived(cacheEntry?.status === 'resolved' && !!cacheEntry.fqn);
  let symbolFqn = $derived(cacheEntry?.fqn);

  // Dev mode detection for tooltip display
  let isDevMode = $derived(() => import.meta.env.DEV);

  // Create tooltip value using state instead of nested derived
  let tooltipText = $state(undefined);

  // Update tooltip text using effect
  $effect(() => {
    if (!isDevMode) {
      tooltipText = undefined;
      return;
    }

    const exists = cacheEntry?.status === 'resolved' && !!cacheEntry.fqn;
    const fqn = cacheEntry?.fqn;

    if (exists && fqn) {
      tooltipText = `${symbolText} → ${fqn}`;
    } else {
      tooltipText = `DEBUG: symbolText="${symbolText}" symbolExists=${exists} symbolFqn="${fqn || 'null'}"`;
    }
  });


  function handleClick(event: MouseEvent) {
    if (!isValidSymbol || !symbolExists) return;

    if (event.button === 0) { // Left click
      log.info(`Left-clicked symbol: ${symbolText}, exists: ${symbolExists}`);
      // TODO: Add navigation logic if needed
    } else if (event.button === 2) { // Right click
      event.preventDefault();
      log.info(`Right-clicked symbol: ${symbolText}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}`);

      // Call Java bridge for right-click with coordinates
      if (window.javaBridge?.onSymbolRightClick) {
        window.javaBridge.onSymbolRightClick(symbolText, !!symbolExists, symbolFqn, event.clientX, event.clientY);
      }
    }
  }
</script>

<code
  class={symbolExists ? 'symbol-exists' : ''}
  data-symbol={isValidSymbol ? symbolText : undefined}
  data-symbol-exists={symbolExists ? 'true' : 'false'}
  data-symbol-fqn={symbolFqn}
  data-symbol-component="true"
  data-symbol-id={componentId}
  title={tooltipText}
  onclick={handleClick}
  oncontextmenu={handleClick}
  role={symbolExists ? 'button' : undefined}
  {...rest}
>
  {@render children?.()}
</code>
