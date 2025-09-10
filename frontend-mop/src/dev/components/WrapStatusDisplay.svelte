<script>
  import { onMount } from 'svelte';
  import { isDebugEnabled } from '../debug';

  let visible = $state(isDebugEnabled('showWrapStatus'));
  let theme = $state('light');
  let wrapMode = $state('scroll');

  function updateStatus() {
    const html = document.querySelector('html');
    if (html) {
      theme = html.classList.contains('theme-dark') ? 'dark' : 'light';
      wrapMode = html.classList.contains('code-wrap-mode') ? 'wrap' : 'scroll';
    }
  }

  function toggleWrapMode() {
    const html = document.querySelector('html');
    if (html) {
      const isCurrentlyWrap = html.classList.contains('code-wrap-mode');
      if (isCurrentlyWrap) {
        html.classList.remove('code-wrap-mode');
        console.info('Wrap mode disabled (via status click)');
      } else {
        html.classList.add('code-wrap-mode');
        console.info('Wrap mode enabled (via status click)');
      }
      // updateStatus will be called automatically via MutationObserver
    }
  }

  // Watch for class changes on the HTML element
  function observeHtmlChanges() {
    const html = document.querySelector('html');
    if (!html) return;

    const observer = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        if (mutation.type === 'attributes' && mutation.attributeName === 'class') {
          updateStatus();
        }
      });
    });

    observer.observe(html, {
      attributes: true,
      attributeFilter: ['class']
    });

    return () => observer.disconnect();
  }

  onMount(() => {
    updateStatus();
    const cleanup = observeHtmlChanges();
    return cleanup;
  });

  // Expose functions globally
  if (typeof window !== 'undefined') {
    window.toggleWrapStatus = () => {
      visible = !visible;
    };
    window.updateWrapStatus = updateStatus;
  }
</script>

{#if visible}
  <div class="wrap-status-display">
    <span class="status-label">DEBUG:</span>
    <span class="theme-status">Theme: {theme === 'dark' ? 'Dark' : 'Light'}</span>
    <span class="separator">|</span>
    <span class="wrap-status clickable" onclick={toggleWrapMode}>Wrap: {wrapMode === 'wrap' ? 'Enabled' : 'Disabled'}</span>
    <button class="close-btn" onclick={() => visible = false}>×</button>
  </div>
{/if}

<style>
  .wrap-status-display {
    position: fixed;
    top: 10px;
    right: 10px;
    background: rgba(0, 0, 0, 0.8);
    color: white;
    padding: 8px 12px;
    border-radius: 6px;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 12px;
    z-index: 9999;
    display: flex;
    align-items: center;
    gap: 8px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
    border: 1px solid rgba(255, 255, 255, 0.1);
  }

  :global(html.theme-light) .wrap-status-display {
    background: rgba(255, 255, 255, 0.95);
    color: #333;
    border: 1px solid rgba(0, 0, 0, 0.1);
  }

  .status-label {
    font-weight: bold;
    color: #ff6b35;
  }

  :global(html.theme-light) .status-label {
    color: #d63031;
  }

  .theme-status, .wrap-status {
    font-weight: 500;
  }

  .wrap-status.clickable {
    cursor: pointer;
    padding: 2px 4px;
    border-radius: 3px;
    transition: background-color 0.2s ease;
  }

  .wrap-status.clickable:hover {
    background-color: rgba(255, 255, 255, 0.1);
  }

  :global(html.theme-light) .wrap-status.clickable:hover {
    background-color: rgba(0, 0, 0, 0.1);
  }

  .separator {
    opacity: 0.6;
  }

  .close-btn {
    background: none;
    border: none;
    color: inherit;
    font-size: 16px;
    cursor: pointer;
    padding: 0;
    margin-left: 4px;
    opacity: 0.7;
    width: 18px;
    height: 18px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .close-btn:hover {
    opacity: 1;
  }
</style>