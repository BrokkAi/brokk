<script lang="ts">
  import { fade } from 'svelte/transition';
  import { spinnerStore } from '../stores/spinnerStore';
</script>

{#if $spinnerStore.visible}
  <div id="spinner" class="spinner-msg" in:fade={{ duration: 150 }} out:fade={{ duration: 100 }}>
    {$spinnerStore.message}
  </div>
{/if}

<style>
  .spinner-msg {
    position: relative;
    align-self: center;
    padding: 0.5em 1em;

    --spinner-text: var(--chat-text, #888);
    color: var(--spinner-text);

    /* Animate pulse */
    animation: spinner-pulse 1.5s ease-in-out infinite alternate;
  }

  @keyframes spinner-pulse {
    from {
      opacity: 1;
    }
    to {
      opacity: 0.5;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .spinner-msg {
      animation: none;
    }
  }
</style>
