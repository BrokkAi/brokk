<script lang="ts">
  import Icon from "@iconify/svelte";
  let { children, ...rest } = $props(); // Svelte 5 props destructuring

  // Reactive state in Svelte 5 (Runes)
  let copied = $state(false);
  let preElem: HTMLElement;
  let copyTimeout: number | null = null;

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
</script>

<div class="custom-code-block">
  <div class="custom-code-header">
    <Icon icon="mdi:code-braces" />
    <span class="language-name">{rest['data-language'] || 'Code'}</span>
    <span class="spacer"></span>
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
      {copied ? 'Copied to clipboard' : ''}
    </span>
  </div>
  <pre bind:this={preElem} {...rest}>{@render children?.()}</pre>
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
    font-size: 0.8rem;
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
  .copy-btn.copied {
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
