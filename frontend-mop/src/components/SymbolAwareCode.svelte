<script lang="ts">
  import {onMount} from 'svelte';
  import {symbolCacheStore, requestSymbolResolution, type SymbolCacheEntry} from '../stores/symbolCacheStore';
  import {createLogger} from '../lib/logging';

  let {children, ...rest} = $props();

  const log = createLogger('symbol-aware-code');

  // Extract symbol text from children
  let symbolText = $state('');
  let isValidSymbol = $state(false);
  let cacheEntry: SymbolCacheEntry | undefined = $state(undefined);
  let contextId = 'main-context';

  // Unique identifier for this component instance
  const componentId = `symbol-${Math.random().toString(36).substr(2, 9)}`;

  // Common keywords/literals across languages that should never be looked up
  const COMMON_KEYWORDS = new Set([
    // Boolean literals
    'true', 'false',
    // Null/undefined
    'null', 'undefined', 'nil', 'none',
    // Common primitive types
    'int', 'string', 'boolean', 'void', 'var', 'let', 'const',
    // Control flow keywords
    'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'default',
    'break', 'continue', 'return',
    // OOP keywords
    'this', 'super', 'self', 'class', 'interface', 'extends', 'implements',
    // Access modifiers
    'public', 'private', 'protected', 'static', 'final', 'abstract',
    // Exception handling
    'try', 'catch', 'finally', 'throw', 'throws',
    // Other common keywords
    'new', 'delete', 'import', 'export', 'from', 'as', 'function', 'def',
    'field', 'module'
  ]);

  // Clean and validate symbol names, filtering out language keywords
  function cleanSymbolName(raw: string): string {
    const trimmed = raw.trim();

    if (trimmed.length < 2 || trimmed.length > 200) {
      return '';
    }

    // Filter out common keywords and literals
    if (COMMON_KEYWORDS.has(trimmed.toLowerCase())) {
      return '';
    }

    return trimmed;
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
          log.debug(`Symbol extracted: "${textContent}"`);
          symbolText = textContent;
          validateAndRequestSymbol();
        }
      }, 0);
    } else {
      validateAndRequestSymbol();
    }
  });

  function validateAndRequestSymbol() {
    // Verify we're in browser environment (not server-side rendering)
    if (typeof window === 'undefined') {
      log.debug('Skipping symbol validation - no browser environment');
      return;
    }

    const cleaned = cleanSymbolName(symbolText);

    if (cleaned) {
      isValidSymbol = true;
      symbolText = cleaned;

      // Request symbol resolution
      requestSymbolResolution(symbolText, contextId).catch(error => {
        log.warn(`Symbol resolution failed for ${symbolText}:`, error);
      });

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



  function handleClick(event: MouseEvent) {
    if (!isValidSymbol || !symbolExists) return;

    if (event.button === 0) { // Left click
      log.info(`Left-clicked symbol: ${symbolText}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}`);

      // Call Java bridge for left-click with coordinates
      if (window.javaBridge?.onSymbolRightClick) {
        window.javaBridge.onSymbolRightClick(symbolText, !!symbolExists, symbolFqn, event.clientX, event.clientY);
      }
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
  onclick={handleClick}
  oncontextmenu={handleClick}
  role={symbolExists ? 'button' : undefined}
  {...rest}
>
  {@render children?.()}
</code>
