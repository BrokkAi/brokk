<script lang="ts">
  import {onMount} from 'svelte';
  import {symbolCacheStore, requestSymbolResolution, subscribeKey, type SymbolCacheEntry} from '../stores/symbolCacheStore';
  import {createLogger} from '../lib/logging';
  import {isDebugEnabled} from '../dev/debug';

  let {children, ...rest} = $props();

  const log = createLogger('symbol-aware-code');

  // Extract symbol text from children
  let symbolText = $state('');
  let extractedText = $state(''); // Store extracted DOM text for fallback rendering
  let isValidSymbol = $state(false);
  let cacheEntry: SymbolCacheEntry | undefined = $state(undefined);
  let contextId = 'main-context';

  // Unique identifier for this component instance
  const componentId = `symbol-${Math.random().toString(36).substr(2, 9)}`;

  // Common keywords/literals across languages that should never be looked up
  // Note: We're being selective here - Java class names like "String" are valid symbols
  const COMMON_KEYWORDS = new Set([
    // Boolean literals
    'true', 'false',
    // Null/undefined
    'null', 'undefined', 'nil', 'none',
    // Common primitive types (but not Java wrapper classes)
    'int', 'boolean', 'void', 'var', 'let', 'const',
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
    'field', 'module',
    // Method names that should be filtered
    'add', 'get', 'put', 'remove', 'contains', 'isEmpty', 'size', 'toString'
  ]);

  log.debug('COMMON_KEYWORDS contains add:', COMMON_KEYWORDS.has('add'));

  // Clean and validate symbol names, filtering out language keywords
  function cleanSymbolName(raw: string): string {
    const trimmed = raw.trim();

    if (trimmed.length < 2 || trimmed.length > 200) {
      log.debug(`Symbol "${trimmed}" filtered out: length check`);
      return '';
    }

    // Filter out common keywords and literals
    const lowerTrimmed = trimmed.toLowerCase();
    const hasKeyword = COMMON_KEYWORDS.has(lowerTrimmed);
    log.debug(`Checking "${trimmed}" (lower: "${lowerTrimmed}") against keywords, found: ${hasKeyword}`);
    if (hasKeyword) {
      log.debug(`Symbol "${trimmed}" filtered out: common keyword`);
      return '';
    }

    log.debug(`Symbol "${trimmed}" passed cleaning`);
    return trimmed;
  }

  // Simple check to avoid obviously invalid symbols (performance optimization)
  function shouldAttemptLookup(symbolText: string): boolean {
    // Skip processing for multi-line text (code blocks)
    if (symbolText.includes('\n')) {
      return false;
    }

    // Very basic checks to avoid sending obviously invalid symbols to backend
    return symbolText.length >= 2 &&
           symbolText.length <= 200 &&
           /^[A-Za-z]/.test(symbolText) && // Must start with a letter
           !/\s/.test(symbolText); // No whitespace
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

    // For inline code elements, svelte-exmarkdown might pass text content in different ways
    // Check if there's direct text content in the rest props
    if (rest && typeof rest === 'object') {
      // Check for common text content properties
      if ('textContent' in rest && typeof rest.textContent === 'string') {
        return rest.textContent;
      }
      if ('innerText' in rest && typeof rest.innerText === 'string') {
        return rest.innerText;
      }
      if ('value' in rest && typeof rest.value === 'string') {
        return rest.value;
      }
      if ('text' in rest && typeof rest.text === 'string') {
        return rest.text;
      }
    }

    // Skip snippet processing entirely for inline code elements
    // The text content will be extracted from DOM after mount
    log.debug('No text content found in props, will extract from DOM after mount');
    return '';
  }

  onMount(() => {
    // Try to extract from props first
    const propsText = extractTextFromChildren();
    if (propsText && !propsText.includes('\n')) {
      log.debug(`Symbol extracted from props: "${propsText}"`);
      extractedText = propsText;
      symbolText = propsText;
      validateAndRequestSymbol();
      return;
    }

    // Fallback to DOM extraction after mount
    setTimeout(() => {
      const thisElement = document.querySelector(`code[data-symbol-id="${componentId}"]`);
      if (thisElement) {
        const textContent = thisElement.textContent?.trim() || '';

        // Skip code blocks (multi-line content) early
        if (textContent.includes('\n')) {
          log.debug(`Skipping code block (contains newlines): "${textContent.substring(0, 50)}..."`);
          return;
        }

        log.debug(`Symbol extracted from DOM: "${textContent}"`);
        extractedText = textContent; // Store for fallback rendering
        symbolText = textContent;
        validateAndRequestSymbol();
      } else {
        log.debug('Could not find element with symbol ID:', componentId);
      }
    }, 0);
  });

  function validateAndRequestSymbol() {
    // Verify we're in browser environment (not server-side rendering)
    if (typeof window === 'undefined') {
      log.debug('Skipping symbol validation - no browser environment');
      return;
    }

    const cleaned = cleanSymbolName(symbolText);

    if (cleaned && shouldAttemptLookup(cleaned)) {
      isValidSymbol = true;
      symbolText = cleaned;

      // Request symbol resolution
      requestSymbolResolution(symbolText, contextId).catch(error => {
        log.warn(`Symbol resolution failed for ${symbolText}:`, error);
      });

    } else {
      log.debug(`Invalid symbol text: '${symbolText}' (cleaned: '${cleaned}')`);
    }
  }

  // Key-scoped subscription - only updates when this specific symbol changes
  let symbolStore: ReturnType<typeof subscribeKey> | undefined = $state(undefined);

  $effect(() => {
    if (isValidSymbol) {
      const cacheKey = `${contextId}:${symbolText}`;
      symbolStore = subscribeKey(cacheKey);
    } else {
      symbolStore = undefined;
    }
  });

  // Subscribe to symbol-specific updates
  $effect(() => {
    if (symbolStore) {
      cacheEntry = $symbolStore;
      log.debug(`Cache entry updated: symbolExists=${symbolExists}, symbolText="${symbolText}", extractedText="${extractedText}"`);
    }
  });

  // Determine if symbol exists and get FQN using derived state
  let symbolExists = $derived(cacheEntry?.status === 'resolved' && !!cacheEntry?.result?.fqn);
  let symbolFqn = $derived(cacheEntry?.result?.fqn);
  let isPartialMatch = $derived(cacheEntry?.result?.isPartialMatch || false);
  let highlightRanges = $derived(cacheEntry?.result?.highlightRanges || []);
  let originalText = $derived(cacheEntry?.result?.originalText);

  // Debug tooltip information
  let showTooltip = $state(false);
  let showDebugTooltips = isDebugEnabled('showTooltips');

  // Generate tooltip content for debug mode
  let tooltipContent = $derived.by(() => {
    if (!showDebugTooltips || !isValidSymbol) return '';

    const parts = [];
    parts.push(`Symbol: ${symbolText}`);

    if (cacheEntry?.result) {
      parts.push(`FQN: ${cacheEntry.result.fqn || 'null'}`);
      parts.push(`Type: ${isPartialMatch ? 'Partial Match' : 'Exact Match'}`);
      if (highlightRanges.length > 0) {
        parts.push(`Highlight Ranges: [${highlightRanges.map(r => `${r[0]}-${r[1]}`).join(', ')}]`);
      }
      if (originalText && originalText !== symbolText) {
        parts.push(`Original: ${originalText}`);
      }
    } else {
      parts.push('Status: Pending/Not Found');
    }

    return parts.join('\n');
  });



  // Add text segmentation for multi-range highlighting
  let textSegments = $derived.by(() => {
    const displayText = symbolText || extractedText;
    if (!symbolExists || highlightRanges.length === 0 || !displayText) {
      return [{ text: displayText || '', highlighted: false }];
    }

    const segments = [];
    let lastIndex = 0;

    // Sort ranges by start position
    const sortedRanges = [...highlightRanges].sort((a, b) => a[0] - b[0]);

    for (const [start, end] of sortedRanges) {
      // Add unhighlighted text before this range
      if (start > lastIndex) {
        segments.push({
          text: displayText.substring(lastIndex, start),
          highlighted: false
        });
      }
      // Add highlighted range
      segments.push({
        text: displayText.substring(start, end),
        highlighted: true
      });
      lastIndex = end;
    }

    // Add remaining unhighlighted text
    if (lastIndex < displayText.length) {
      segments.push({
        text: displayText.substring(lastIndex),
        highlighted: false
      });
    }

    return segments;
  });

  function handleClick(event: MouseEvent) {
    if (!isValidSymbol || !symbolExists) return;

    // For partial matches, only handle clicks on highlighted spans
    if (isPartialMatch) {
      const target = event.target as HTMLElement;
      // Only proceed if click was on a .symbol-highlight span
      if (!target?.classList?.contains('symbol-highlight')) {
        return;
      }
    }

    // For partial matches, navigate using the extracted class from the original text
    const displayText = isPartialMatch ? `${originalText} (partial match)` : symbolText;

    if (event.button === 0) { // Left click
      log.info(`Left-clicked symbol: ${displayText}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}, isPartialMatch: ${isPartialMatch}`);

      // Call Java bridge for left-click with coordinates
      if (window.javaBridge?.onSymbolClick) {
        window.javaBridge.onSymbolClick(symbolText, !!symbolExists, symbolFqn, event.clientX, event.clientY);
      }
    } else if (event.button === 2) { // Right click
      event.preventDefault();
      log.info(`Right-clicked symbol: ${displayText}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}, isPartialMatch: ${isPartialMatch}`);

      // Call Java bridge for right-click with coordinates
      if (window.javaBridge?.onSymbolClick) {
        window.javaBridge.onSymbolClick(symbolText, !!symbolExists, symbolFqn, event.clientX, event.clientY);
      }
    }
  }

  // Mouse event handlers for tooltip
  function handleMouseEnter() {
    if (showDebugTooltips && isValidSymbol && tooltipContent) {
      showTooltip = true;
    }
  }

  function handleMouseLeave() {
    showTooltip = false;
  }
</script>

<code
  class={symbolExists ? (isPartialMatch ? 'symbol-exists partial-match' : 'symbol-exists') : ''}
  data-symbol={isValidSymbol ? symbolText : undefined}
  data-symbol-exists={symbolExists ? 'true' : 'false'}
  data-symbol-fqn={symbolFqn}
  data-symbol-partial={isPartialMatch ? 'true' : 'false'}
  data-symbol-original={isPartialMatch ? originalText : undefined}
  data-symbol-component="true"
  data-symbol-id={componentId}
  onclick={handleClick}
  oncontextmenu={handleClick}
  onmouseenter={handleMouseEnter}
  onmouseleave={handleMouseLeave}
  role={symbolExists ? 'button' : undefined}
  {...rest}
  title={showDebugTooltips && isValidSymbol ? tooltipContent : rest.title}
>
  {#if symbolExists}
    {@const displayText = symbolText || extractedText}
    {console.log(`RENDER: symbolExists=true, displayText="${displayText}", ranges=${highlightRanges.length}, segments=`, textSegments)}
    {#if displayText && highlightRanges.length > 0}
      <!-- Multi-range highlighting for partial matches -->
      {#each textSegments as segment}
        {#if segment.highlighted}
          <span class="symbol-highlight">{segment.text}</span>
        {:else}
          {segment.text}
        {/if}
      {/each}
    {:else if displayText}
      <!-- Full text highlighting for exact matches -->
      <span class="symbol-highlight">{displayText}</span>
    {:else}
      <!-- Fallback to children if no text available -->
      {console.log(`RENDER: No displayText available, symbolText="${symbolText}", extractedText="${extractedText}"`)}
      {@render children?.()}
    {/if}
  {:else}
    <!-- Always render the original content while waiting for symbol resolution -->
    {@render children?.()}
  {/if}
</code>
