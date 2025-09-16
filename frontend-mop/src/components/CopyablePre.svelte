<script lang="ts">
  import Icon from "@iconify/svelte";
  let { children, ...rest } = $props(); // Svelte 5 props destructuring

  // Reactive state in Svelte 5 (Runes)
  let copied = $state(false);
  let captured = $state(false);
  let preElem: HTMLElement;
  let copyTimeout: number | null = null;
  let captureTimeout: number | null = null;

  function handleWheel(event: WheelEvent) {
    // Only intervene if the pre element doesn't need to scroll vertically
    const { scrollTop, scrollHeight, clientHeight } = preElem;
    const canScrollDown = scrollTop < scrollHeight - clientHeight;
    const canScrollUp = scrollTop > 0;

    const isScrollingDown = event.deltaY > 0;
    const isScrollingUp = event.deltaY < 0;

    // If we can't scroll vertically in the intended direction, pass to parent
    if ((isScrollingDown && !canScrollDown) || (isScrollingUp && !canScrollUp)) {
      event.preventDefault();
      event.stopPropagation();

      // Find scrollable parent (more generic than .chat-container)
      let parent = preElem.parentElement;
      while (parent && parent.scrollHeight <= parent.clientHeight) {
        parent = parent.parentElement;
      }

      if (parent) {
        parent.scrollBy(0, event.deltaY);
      }
    }
  }

  async function copyToClipboard() {
    const text = preElem?.innerText ?? '';
    if (!text) {
      return;
    }

    let success = false;

    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        success = true;
      } else {
        // Fallback for older browsers or JavaFX WebView
        const range = document.createRange();
        range.selectNodeContents(preElem);
        const sel = window.getSelection();
        sel?.removeAllRanges();
        sel?.addRange(range);
        success = document.execCommand('copy');
        sel?.removeAllRanges();
      }
    } catch (e) {
      console.warn('Copy to clipboard failed', e);
      success = false;
    } finally {
      if (success) {
        setCopiedTransient();
      }
    }
  }

  function captureToWorkspace() {
    const text = preElem?.innerText ?? '';
    if (!text) {
      return;
    }

    const javaBridge = window.javaBridge;
    if (javaBridge.captureText) {
      javaBridge.captureText(text);
      setCapturedTransient();
    } else {
      console.warn('`window.brokk.captureText` is not available');
    }
  }

  function setCopiedTransient() {
    copied = true;
    if (copyTimeout !== null) {
      clearTimeout(copyTimeout);
    }
    copyTimeout = window.setTimeout(() => {
      copied = false;
      copyTimeout = null;
    }, 1200);
  }

  function setCapturedTransient() {
    captured = true;
    if (captureTimeout !== null) {
      clearTimeout(captureTimeout);
    }
    captureTimeout = window.setTimeout(() => {
      captured = false;
      captureTimeout = null;
    }, 1200);
  }
</script>

<div class="custom-code-block">
  <div class="custom-code-header">
    <Icon icon="mdi:code-braces" />
    <span class="language-name">{rest['data-language'] || 'Code'}</span>
    <span class="spacer"></span>
    <button
      type="button"
      class="copy-btn"
      class:captured={captured}
      on:click={captureToWorkspace}
      aria-label={captured ? 'Captured!' : 'Capture code to workspace'}
      title={captured ? 'Captured!' : 'Capture code to workspace'}
    >
      <Icon icon={captured ? 'mdi:check' : 'mdi:camera-plus-outline'} />
    </button>
    <button
      type="button"
      class="copy-btn"
      class:copied={copied}
      on:click={copyToClipboard}
      aria-label={copied ? 'Copied!' : 'Copy code to clipboard'}
      title={copied ? 'Copied!' : 'Copy code to clipboard'}
    >
      <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} />
    </button>
    <span class="sr-only" aria-live="polite" role="status">
      {copied ? 'Copied to clipboard' : captured ? 'Captured to workspace' : ''}
    </span>
  </div>
  <pre bind:this={preElem} on:wheel={handleWheel} {...rest}>{@render children?.()}</pre>
</div>

<style>
  .custom-code-block {
    position: relative;
    overflow: hidden;
    margin: 0.75em 0;
    border-radius: 10px;
    border: 1px solid var(--code-block-border);
    border-left: 5px solid var(--code-block-border);
  }

  .custom-code-header {
    display: flex;
    align-items: center;
    gap: 0.35em;
    padding: 0.3em 0.6em;
    font-size: 0.8em;
    font-weight: 600;
    background: var(--border-color-hex);
  }

  .language-name {
    text-transform: uppercase;
    opacity: 0.7;
  }

  .spacer {
    flex: 1;
  }

  .copy-btn {
    background: transparent;
    border: none;
    cursor: pointer;
    color: var(--chat-text);
    opacity: 0.6;
    padding: 0.2em;
    transition: opacity 0.2s;
  }

  .copy-btn:hover {
    opacity: 1;
  }

  .copy-btn:focus {
    outline: none;
    opacity: 1;
  }

  /* Visual hint on success */
  .copy-btn.copied,
  .copy-btn.captured {
    color: var(--git-status-added);
    opacity: 1;
  }

  /* Visually hidden, screen-reader only */
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
  }
</style>
