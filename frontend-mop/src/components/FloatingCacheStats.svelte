<script lang="ts">
  import {symbolCacheStore, getCacheStats, getCacheSize, CACHE_CONFIG} from '../stores/symbolCacheStore';
  import {isDebugEnabled} from '../dev/debug';
  import {onMount, onDestroy} from 'svelte';

  // Use centralized debug configuration
  const SHOW_CACHE_STATS = isDebugEnabled('showCacheStats');

  let stats = getCacheStats();
  let cacheSize = 0;
  let hitRate = 0;

  // Update stats periodically and reactively
  let interval: number;
  let unsubscribe: (() => void) | null = null;

  function updateStats() {
    stats = getCacheStats();
    cacheSize = getCacheSize();

    // Calculate hit rate percentage
    const total = stats.hits + stats.misses;
    hitRate = total > 0 ? Math.round((stats.hits / total) * 100) : 0;
  }

  onMount(() => {
    updateStats();

    // Only use interval polling to avoid reactive subscription issues
    interval = window.setInterval(updateStats, 1000);
  });

  onDestroy(() => {
    if (interval) {
      clearInterval(interval);
    }
  });
</script>

{#if SHOW_CACHE_STATS}
<div class="floating-cache-stats">
  <div class="stats-header">Cache Stats</div>
  <div class="stats-content">
    <div class="stat-row">
      <span class="stat-label">Hit Rate:</span>
      <span class="stat-value">{hitRate}%</span>
    </div>
    <div class="stat-row">
      <span class="stat-label">Requests:</span>
      <span class="stat-value">{stats.requests}</span>
    </div>
    <div class="stat-row">
      <span class="stat-label">Hits/Misses:</span>
      <span class="stat-value">{stats.hits}/{stats.misses}</span>
    </div>
    <div class="stat-row">
      <span class="stat-label">Cache Size:</span>
      <span class="stat-value">{cacheSize}/{CACHE_CONFIG.SYMBOL_CACHE_LIMIT}</span>
    </div>
    <div class="stat-row">
      <span class="stat-label">Found/Not Found:</span>
      <span class="stat-value">{stats.symbolsFound}/{stats.symbolsNotFound}</span>
    </div>
  </div>
</div>
{/if}

<style>
  .floating-cache-stats {
    position: fixed;
    top: 10px;
    right: 10px;
    z-index: 9999;
    background: var(--chat-background);
    border: 1px solid var(--border-color-hex);
    border-radius: 4px;
    padding: 8px;
    font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
    font-size: 11px;
    line-height: 1.2;
    opacity: 0.9;
    backdrop-filter: blur(4px);
    min-width: 140px;
  }

  .stats-header {
    font-weight: bold;
    margin-bottom: 4px;
    color: white;
    border-bottom: 1px solid var(--border-color-hex);
    padding-bottom: 2px;
  }

  .stats-content {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .stat-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .stat-label {
    color: white;
    margin-right: 8px;
  }

  .stat-value {
    color: white;
    font-weight: bold;
    text-align: right;
  }

  /* Theme-specific adjustments */
  :global(.theme-dark) .floating-cache-stats {
    background: rgba(40, 44, 52, 0.95);
    border-color: #4a5568;
  }

  :global(.theme-light) .floating-cache-stats {
    background: rgba(255, 255, 255, 0.95);
    border-color: #e2e8f0;
  }
</style>
