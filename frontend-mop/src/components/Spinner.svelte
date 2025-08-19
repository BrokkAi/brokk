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

    /* Base + highlight colors */
    --spinner-text: var(--chat-text, #888);
    --spinner-glint: rgba(255, 255, 255, 0.4); /* For dark theme */

    /* Shimmer gradient */
    background-image: linear-gradient(
      90deg,
      var(--spinner-text) 0%,
      var(--spinner-text) 40%,
      var(--spinner-glint) 50%,
      var(--spinner-text) 60%,
      var(--spinner-text) 100%
    );
    background-size: 200% 100%;
    background-repeat: no-repeat;

    /* Clip gradient to text */
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;

    /* Animate gradient sweep */
    animation: spinner-shimmer 1.6s ease-in-out infinite;
  }

  :global(.theme-light) .spinner-msg {
    --spinner-glint: rgba(0, 0, 0, 0.15);
  }

  @keyframes spinner-shimmer {
    from {
      background-position: 0 0;
    }
    to {
      background-position: 100% 0;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .spinner-msg {
      animation: none;

      /* Fallback to solid color when animation is disabled */
      background-image: none;
      -webkit-background-clip: unset;
      background-clip: unset;
      color: var(--spinner-text);
    }
  }
</style>
