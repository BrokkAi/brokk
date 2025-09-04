<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { getCacheStats, getCacheSize, getInflightRequestsCount, getCacheContents } from '../../stores/symbolCacheStore';
  import { isDebugEnabled } from '../debug';

  let stats = $state({
    requests: 0,
    hits: 0,
    misses: 0,
    evictions: 0,
    totalSymbolsProcessed: 0,
    responses: 0,
    symbolsFound: 0,
    symbolsNotFound: 0,
    cacheSize: 0,
    inflightRequests: 0
  });

  let updateInterval: number;

  // Only show if debug is enabled
  let showStats = $state(isDebugEnabled('showCacheStats'));

  // Copy feedback state
  let copyStatus = $state<'idle' | 'copying' | 'success' | 'error'>('idle');

  async function copyCacheToClipboard() {
    try {
      copyStatus = 'copying';

      const cacheContents = getCacheContents();
      const formattedData = JSON.stringify(cacheContents, null, 2);

      // Try to use the modern clipboard API
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(formattedData);
      } else {
        // Fallback for older browsers
        const textArea = document.createElement('textarea');
        textArea.value = formattedData;
        textArea.style.position = 'fixed';
        textArea.style.left = '-999999px';
        textArea.style.top = '-999999px';
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();
        document.execCommand('copy');
        textArea.remove();
      }

      copyStatus = 'success';
      setTimeout(() => copyStatus = 'idle', 2000);
    } catch (error) {
      console.error('Failed to copy cache contents:', error);
      copyStatus = 'error';
      setTimeout(() => copyStatus = 'idle', 2000);
    }
  }

  function updateStats() {
    if (!showStats) return;

    const cacheStats = getCacheStats();
    stats = {
      ...cacheStats,
      cacheSize: getCacheSize(),
      inflightRequests: getInflightRequestsCount()
    };
  }

  onMount(() => {
    if (showStats) {
      updateStats();
      updateInterval = window.setInterval(updateStats, 1000); // Update every second
    }
  });

  onDestroy(() => {
    if (updateInterval) {
      clearInterval(updateInterval);
    }
  });

  // Calculate hit rate
  let hitRate = $derived(stats.requests > 0 ? (stats.hits / stats.requests * 100).toFixed(1) : '0.0');
</script>

{#if showStats}
<div class="cache-stats-debug">
  <div class="cache-stats-header">
    <strong>🔍 Symbol Cache Debug</strong>
    <div class="header-buttons">
      <button
        class="copy-button"
        onclick={copyCacheToClipboard}
        disabled={copyStatus === 'copying'}
        title="Copy cache contents to clipboard"
      >
        {#if copyStatus === 'copying'}
          ⏳
        {:else if copyStatus === 'success'}
          ✅
        {:else if copyStatus === 'error'}
          ❌
        {:else}
          📋
        {/if}
      </button>
      <button class="toggle-button" onclick={() => showStats = !showStats}>×</button>
    </div>
  </div>

  <div class="cache-stats-grid">
    <div class="stat-item">
      <span class="stat-label">Cache Size:</span>
      <span class="stat-value">{stats.cacheSize}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">In-Flight:</span>
      <span class="stat-value">{stats.inflightRequests}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Requests:</span>
      <span class="stat-value">{stats.requests}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Hit Rate:</span>
      <span class="stat-value">{hitRate}%</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Hits:</span>
      <span class="stat-value stat-good">{stats.hits}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Misses:</span>
      <span class="stat-value stat-warning">{stats.misses}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Found:</span>
      <span class="stat-value stat-good">{stats.symbolsFound}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Not Found:</span>
      <span class="stat-value stat-muted">{stats.symbolsNotFound}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Responses:</span>
      <span class="stat-value">{stats.responses}</span>
    </div>

    <div class="stat-item">
      <span class="stat-label">Evictions:</span>
      <span class="stat-value stat-error">{stats.evictions}</span>
    </div>
  </div>
</div>
{/if}

<style>
  .cache-stats-debug {
    position: fixed;
    top: 10px;
    right: 10px;
    background: rgba(0, 0, 0, 0.9);
    color: #fff;
    border: 1px solid #333;
    border-radius: 8px;
    padding: 12px;
    font-family: 'Fira Code', monospace;
    font-size: 11px;
    min-width: 200px;
    z-index: 9999;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  }

  .cache-stats-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
    padding-bottom: 8px;
    border-bottom: 1px solid #333;
  }

  .header-buttons {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .copy-button {
    background: none;
    border: none;
    color: #fff;
    cursor: pointer;
    font-size: 14px;
    padding: 2px;
    line-height: 1;
    transition: all 0.2s ease;
    border-radius: 3px;
  }

  .copy-button:hover:not(:disabled) {
    background: rgba(255, 255, 255, 0.1);
    transform: scale(1.1);
  }

  .copy-button:disabled {
    cursor: wait;
    opacity: 0.7;
  }

  .toggle-button {
    background: none;
    border: none;
    color: #fff;
    cursor: pointer;
    font-size: 16px;
    padding: 0;
    line-height: 1;
  }

  .toggle-button:hover {
    color: #ff6b6b;
  }

  .cache-stats-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 4px;
  }

  .stat-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .stat-label {
    color: #aaa;
    font-weight: 500;
  }

  .stat-value {
    color: #fff;
    font-weight: bold;
    text-align: right;
  }

  .stat-good {
    color: #51cf66;
  }

  .stat-warning {
    color: #ffd43b;
  }

  .stat-error {
    color: #ff6b6b;
  }

  .stat-muted {
    color: #868e96;
  }
</style>