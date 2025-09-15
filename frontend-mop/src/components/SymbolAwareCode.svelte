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


  // Clean and validate symbol names, filtering out language keywords
  function cleanSymbolName(raw: string): string {
    const trimmed = raw.trim();

    if (trimmed.length < 2 || trimmed.length > 200) {
      return '';
    }

    // For partial matches like "List.add", don't filter based on method names
    // Only filter if the entire symbol is a keyword (not just part of it)
    const lowerTrimmed = trimmed.toLowerCase();

    // Allow symbols with dots (method references) even if they contain method names
    if (trimmed.includes('.')) {
      return trimmed;
    }

    const hasKeyword = COMMON_KEYWORDS.has(lowerTrimmed);
    if (hasKeyword) {
      return '';
    }

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
    return '';
  }

  onMount(() => {
    // Try to extract from props first
    const propsText = extractTextFromChildren();
    if (propsText && !propsText.includes('\n')) {
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
          return;
        }

        extractedText = textContent; // Store for fallback rendering
        symbolText = textContent;
        validateAndRequestSymbol();
      }
    }, 0);
  });

  function validateAndRequestSymbol() {
    // Verify we're in browser environment (not server-side rendering)
    if (typeof window === 'undefined') {
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
    }
  });


  // Determine if symbol exists and get FQN using derived state
  let symbolExists = $derived(cacheEntry?.status === 'resolved' && !!cacheEntry?.result?.fqn);
  let symbolFqn = $derived(cacheEntry?.result?.fqn);
  let isPartialMatch = $derived(cacheEntry?.result?.isPartialMatch || false);
  let highlightRanges = $derived(cacheEntry?.result?.highlightRanges || []);
  let originalText = $derived(cacheEntry?.result?.originalText);
  let confidence = $derived(cacheEntry?.result?.confidence || 100);
  let processingTimeMs = $derived(cacheEntry?.result?.processingTimeMs || 0);

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
      parts.push(`Confidence: ${confidence}%`);
      if (processingTimeMs > 0) {
        parts.push(`Processing Time: ${processingTimeMs}ms`);
      }
      if (highlightRanges.length > 0) {
        parts.push(`Highlight Ranges: [${highlightRanges.map(r => `${r.start}-${r.end}`).join(', ')}]`);
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
    const sortedRanges = [...highlightRanges].sort((a, b) => a.start - b.start);

    for (const range of sortedRanges) {
      const {start, end} = range;

      // Add unhighlighted text before this range
      if (start > lastIndex) {
        const beforeText = displayText.substring(lastIndex, start);
        segments.push({
          text: beforeText,
          highlighted: false
        });
      }
      // Add highlighted range
      const highlightText = displayText.substring(start, end);
      segments.push({
        text: highlightText,
        highlighted: true
      });
      lastIndex = end;
    }

    // Add remaining unhighlighted text
    if (lastIndex < displayText.length) {
      const afterText = displayText.substring(lastIndex);
      segments.push({
        text: afterText,
        highlighted: false
      });
    }

    return segments;
  });


  // Mouse event handlers for tooltip
  function handleMouseEnter() {
    if (showDebugTooltips && isValidSymbol && tooltipContent) {
      showTooltip = true;
    }
  }

  function handleMouseLeave() {
    showTooltip = false;
  }

  // Single event handler for both left and right clicks
  function handleClick(event: MouseEvent) {
    if (!isValidSymbol || !symbolExists) return;

    const target = event.target as HTMLElement;
    const isClickOnHighlight = target.classList.contains('symbol-highlight');
    const isValidClick = !isPartialMatch || isClickOnHighlight;

    if (!isValidClick) return;

    const clickedText = (isPartialMatch && isClickOnHighlight)
      ? (target.textContent || symbolText)
      : symbolText;

    const displayText = isPartialMatch
      ? `${clickedText} (from ${originalText})`
      : symbolText;

    if (event.button === 0) { // Left click
      log.info(`Left-clicked symbol: ${displayText}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}, isPartialMatch: ${isPartialMatch}`);

      if (window.javaBridge?.onSymbolClick) {
        window.javaBridge.onSymbolClick(clickedText,
                                        !!symbolExists,
                                        symbolFqn,
                                        event.clientX,
                                        event.clientY);
      }
    } else if (event.button === 2) { // Right click
      event.preventDefault();
      event.stopPropagation();

      log.info(`Right-clicked symbol: ${displayText}, exists: ${symbolExists}, fqn: ${symbolFqn || 'null'}, isPartialMatch: ${isPartialMatch}`);

      if (window.javaBridge?.onSymbolClick) {
        window.javaBridge.onSymbolClick(clickedText,
                                        !!symbolExists,
                                        symbolFqn,
                                        event.clientX,
                                        event.clientY);
      }
    }
  }

  // Handle right-click via mousedown to prevent browser default behavior
  function handleMouseDown(event: MouseEvent) {
    if (event.button === 2 && isValidSymbol && symbolExists) {
      event.preventDefault();
      event.stopPropagation();
      handleClick(event);
    }
  }

  function handleContextMenu(event: MouseEvent) {
    if (isValidSymbol && symbolExists) {
      event.preventDefault();
      event.stopPropagation();
      return false;
    }
  }

  // Get CSS class based on confidence level for visual feedback
  function getConfidenceClass(confidence: number): string {
    if (confidence >= 90) return 'confidence-high';
    if (confidence >= 70) return 'confidence-medium';
    if (confidence >= 40) return 'confidence-low';
    return 'confidence-very-low';
  }



</script>

<code
  class={symbolExists ?
    `symbol-exists ${isPartialMatch ? 'partial-match' : ''} ${getConfidenceClass(confidence)}`.trim()
    : ''}
  data-symbol={isValidSymbol ? symbolText : undefined}
  data-symbol-exists={symbolExists ? 'true' : 'false'}
  data-symbol-fqn={symbolFqn}
  data-symbol-partial={isPartialMatch ? 'true' : 'false'}
  data-symbol-original={isPartialMatch ? originalText : undefined}
  data-symbol-confidence={symbolExists ? confidence : undefined}
  data-symbol-processing-time={processingTimeMs > 0 ? processingTimeMs : undefined}
  data-symbol-component="true"
  data-symbol-id={componentId}
  onmouseenter={handleMouseEnter}
  onmouseleave={handleMouseLeave}
  onmousedown={handleMouseDown}
  onclick={handleClick}
  oncontextmenu={handleContextMenu}
  role={symbolExists ? 'button' : undefined}
  {...rest}
  title={showDebugTooltips && isValidSymbol ? tooltipContent : rest.title}
>
  {#if symbolExists}
    {@const displayText = symbolText || extractedText}
    {#if displayText && highlightRanges.length > 0}
      <!-- Multi-range highlighting for partial matches -->
      {#each textSegments as segment, index}
        {#if segment.highlighted}
          <span
            class="symbol-highlight"
          >{segment.text}</span>
        {:else}
          <span class="symbol-non-highlight">{segment.text}</span>
        {/if}
      {/each}
    {:else if displayText}
      <!-- Full text highlighting for exact matches -->
      <span
        class="symbol-highlight"
      >{displayText}</span>
    {:else}
      <!-- Fallback to children if no text available -->
      {@render children?.()}
    {/if}
  {:else}
    <!-- Always render the original content while waiting for symbol resolution -->
    {@render children?.()}
  {/if}
</code>
