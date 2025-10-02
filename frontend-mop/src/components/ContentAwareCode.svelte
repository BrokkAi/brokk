<script lang="ts">
  import {onMount} from 'svelte';
  import {symbolCacheStore, requestSymbolResolution, subscribeKey, type SymbolCacheEntry} from '../stores/symbolCacheStore';
  import {filePathCacheStore, requestFilePathResolution, subscribeKey as subscribeFilePathKey, type FilePathCacheEntry, type ProjectFileMatch} from '../stores/filePathCacheStore';
  import {tryFilePathDetection} from '../lib/filePathDetection';
  import {createLogger} from '../lib/logging';
  import {isDebugEnabled} from '../dev/debug';

  let {children, ...rest} = $props();

  const log = createLogger('content-aware-code');

  // Extract text from children
  let extractedText = $state('');
  let contextId = 'main-context';

  // Symbol detection state
  let symbolText = $state('');
  let isValidSymbol = $state(false);
  let symbolCacheEntry: SymbolCacheEntry | undefined = $state(undefined);

  // File path detection state
  let filePathText = $state('');
  let isValidFilePath = $state(false);
  let filePathCacheEntry: FilePathCacheEntry | undefined = $state(undefined);

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

  // Track last processed text to detect changes
  let lastProcessedText = $state('');

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

  // Reset all state when content changes
  function resetState() {
    extractedText = '';
    symbolText = '';
    isValidSymbol = false;
    symbolCacheEntry = undefined;
    filePathText = '';
    isValidFilePath = false;
    filePathCacheEntry = undefined;
    symbolStore = undefined;
    filePathStore = undefined;
    lastProcessedText = '';
  }

  // Process new content
  function processContent(text: string) {
    if (text === lastProcessedText) {
      return; // No change, skip processing
    }

    lastProcessedText = text;
    extractedText = text;
    validateAndRequestContent(text);
  }

  onMount(() => {
    // Try to extract from props first
    const propsText = extractTextFromChildren();
    if (propsText && !propsText.includes('\n')) {
      processContent(propsText);
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

        processContent(textContent);
      }
    }, 0);
  });

  // Reactive effect to detect when children/content changes after mount
  // This handles the streaming markdown case where children prop updates
  $effect(() => {
    // Watch for changes in the rest props (which include children)
    // Re-extract and re-validate when they change
    const propsText = extractTextFromChildren();
    if (propsText && propsText !== lastProcessedText) {
      // Content has changed, reset and reprocess
      resetState();
      processContent(propsText);
    }
  });

  // Main detection function - tries files first, then symbols
  function validateAndRequestContent(text: string) {
    // Verify we're in browser environment (not server-side rendering)
    if (typeof window === 'undefined') {
      return;
    }

    // Step 1: Try file path detection first
    const filePathResult = tryFilePathDetection(text);
    if (filePathResult.isValidPath) {
      isValidFilePath = true;
      filePathText = filePathResult.cleanPath;

      // Request file path resolution
      requestFilePathResolution(filePathText, contextId).catch(error => {
        log.warn(`File path resolution failed for ${filePathText}:`, error);
      });
      return;
    }

    // Step 2: Fallback to symbol detection
    const cleaned = cleanSymbolName(text);
    if (cleaned && shouldAttemptLookup(cleaned)) {
      isValidSymbol = true;
      symbolText = cleaned;

      // Request symbol resolution
      requestSymbolResolution(symbolText, contextId).catch(error => {
        log.warn(`Symbol resolution failed for ${symbolText}:`, error);
      });
    }
  }

  // Key-scoped subscriptions - only update when specific cache keys change
  let symbolStore: ReturnType<typeof subscribeKey> | undefined = $state(undefined);
  let filePathStore: ReturnType<typeof subscribeFilePathKey> | undefined = $state(undefined);

  $effect(() => {
    if (isValidSymbol) {
      const cacheKey = `${contextId}:${symbolText}`;
      symbolStore = subscribeKey(cacheKey);
    } else {
      symbolStore = undefined;
    }
  });

  $effect(() => {
    if (isValidFilePath) {
      const cacheKey = `${contextId}:${filePathText}`;
      filePathStore = subscribeFilePathKey(cacheKey);
    } else {
      filePathStore = undefined;
    }
  });

  // Subscribe to cache updates
  $effect(() => {
    if (symbolStore) {
      symbolCacheEntry = $symbolStore;
    }
  });

  $effect(() => {
    if (filePathStore) {
      filePathCacheEntry = $filePathStore;
    }
  });


  // Derived states for symbols
  let symbolExists = $derived(symbolCacheEntry?.status === 'resolved' && !!symbolCacheEntry?.result?.fqn);
  let symbolFqn = $derived(symbolCacheEntry?.result?.fqn);
  let isPartialMatch = $derived(symbolCacheEntry?.result?.isPartialMatch || false);
  let highlightRanges = $derived(symbolCacheEntry?.result?.highlightRanges || []);
  let originalText = $derived(symbolCacheEntry?.result?.originalText);
  let symbolConfidence = $derived(symbolCacheEntry?.result?.confidence || 100);
  let symbolProcessingTimeMs = $derived(symbolCacheEntry?.result?.processingTimeMs || 0);

  // Derived states for file paths
  let filePathExists = $derived(filePathCacheEntry?.status === 'resolved' && !!filePathCacheEntry?.result?.exists);
  let filePathMatches = $derived(filePathCacheEntry?.result?.matches || []);
  let filePathConfidence = $derived(filePathCacheEntry?.result?.confidence || 100);
  let filePathProcessingTimeMs = $derived(filePathCacheEntry?.result?.processingTimeMs || 0);

  // Combined derived states - prioritize file paths over symbols
  let contentExists = $derived(filePathExists || symbolExists);
  let confidence = $derived(filePathExists ? filePathConfidence : symbolConfidence);
  let processingTimeMs = $derived(filePathExists ? filePathProcessingTimeMs : symbolProcessingTimeMs);

  // Debug tooltip information
  let showTooltip = $state(false);
  let showDebugTooltips = isDebugEnabled('showTooltips');

  // Generate tooltip content for debug mode
  let tooltipContent = $derived.by(() => {
    if (!showDebugTooltips || (!isValidSymbol && !isValidFilePath)) return '';

    const parts = [];

    if (isValidFilePath) {
      parts.push(`File Path: ${filePathText}`);
      if (filePathCacheEntry?.result) {
        parts.push(`Exists: ${filePathExists}`);
        parts.push(`Matches: ${filePathMatches.length}`);
        parts.push(`Confidence: ${filePathConfidence}%`);
        if (filePathProcessingTimeMs > 0) {
          parts.push(`Processing Time: ${filePathProcessingTimeMs}ms`);
        }
        if (filePathMatches.length > 0) {
          parts.push(`Files: [${filePathMatches.map(m => m.relativePath).join(', ')}]`);
        }
      } else {
        parts.push('Status: Pending/Not Found');
      }
    } else if (isValidSymbol) {
      parts.push(`Symbol: ${symbolText}`);
      if (symbolCacheEntry?.result) {
        parts.push(`FQN: ${symbolCacheEntry.result.fqn || 'null'}`);
        parts.push(`Type: ${isPartialMatch ? 'Partial Match' : 'Exact Match'}`);
        parts.push(`Confidence: ${symbolConfidence}%`);
        if (symbolProcessingTimeMs > 0) {
          parts.push(`Processing Time: ${symbolProcessingTimeMs}ms`);
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
    if (showDebugTooltips && (isValidSymbol || isValidFilePath) && tooltipContent) {
      showTooltip = true;
    }
  }

  function handleMouseLeave() {
    showTooltip = false;
  }

  // Single event handler for both left and right clicks
  function handleClick(event: MouseEvent) {
    // Handle file path clicks first (priority)
    if (isValidFilePath && filePathExists) {
      const matchesJson = JSON.stringify(filePathMatches);

      if (event.button === 0) { // Left click
        log.info(`Left-clicked file path: ${filePathText}, exists: ${filePathExists}, matches: ${filePathMatches.length}`);

        if (window.javaBridge?.onFilePathClick) {
          window.javaBridge.onFilePathClick(filePathText,
                                          !!filePathExists,
                                          matchesJson,
                                          event.clientX,
                                          event.clientY);
        }
      } else if (event.button === 2) { // Right click
        event.preventDefault();
        event.stopPropagation();

        log.info(`Right-clicked file path: ${filePathText}, exists: ${filePathExists}, matches: ${filePathMatches.length}`);

        if (window.javaBridge?.onFilePathClick) {
          window.javaBridge.onFilePathClick(filePathText,
                                          !!filePathExists,
                                          matchesJson,
                                          event.clientX,
                                          event.clientY);
        }
      }
      return;
    }

    // Handle symbol clicks (fallback)
    if (isValidSymbol && symbolExists) {
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
  }

  // Handle right-click via mousedown to prevent browser default behavior
  function handleMouseDown(event: MouseEvent) {
    if (event.button === 2 && contentExists) {
      event.preventDefault();
      event.stopPropagation();
      handleClick(event);
    }
  }

  function handleContextMenu(event: MouseEvent) {
    if (contentExists) {
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
  class={contentExists ?
    `content-exists ${filePathExists ? 'file-path-exists' : ''} ${symbolExists ? 'symbol-exists' : ''} ${isPartialMatch ? 'partial-match' : ''} ${getConfidenceClass(confidence)}`.trim()
    : ''}
  data-symbol={isValidSymbol ? symbolText : undefined}
  data-symbol-exists={symbolExists ? 'true' : 'false'}
  data-symbol-fqn={symbolFqn}
  data-symbol-partial={isPartialMatch ? 'true' : 'false'}
  data-symbol-original={isPartialMatch ? originalText : undefined}
  data-symbol-confidence={symbolExists ? symbolConfidence : undefined}
  data-symbol-processing-time={symbolProcessingTimeMs > 0 ? symbolProcessingTimeMs : undefined}
  data-file-path={isValidFilePath ? filePathText : undefined}
  data-file-path-exists={filePathExists ? 'true' : 'false'}
  data-file-path-matches={filePathExists ? filePathMatches.length : undefined}
  data-file-path-confidence={filePathExists ? filePathConfidence : undefined}
  data-file-path-processing-time={filePathProcessingTimeMs > 0 ? filePathProcessingTimeMs : undefined}
  data-content-component="true"
  data-symbol-id={componentId}
  onmouseenter={handleMouseEnter}
  onmouseleave={handleMouseLeave}
  onmousedown={handleMouseDown}
  onclick={handleClick}
  oncontextmenu={handleContextMenu}
  role={contentExists ? 'button' : undefined}
  {...rest}
  title={showDebugTooltips && (isValidSymbol || isValidFilePath) ? tooltipContent : rest.title}
>
  {#if filePathExists}
    <!-- File path highlighting -->
    {@const displayText = filePathText || extractedText}
    {#if displayText}
      <span class="file-path-highlight">{displayText}</span>
    {:else}
      {@render children?.()}
    {/if}
  {:else if symbolExists}
    <!-- Symbol highlighting -->
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
    <!-- Always render the original content while waiting for resolution -->
    {@render children?.()}
  {/if}
</code>
