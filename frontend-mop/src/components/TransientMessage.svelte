<script lang="ts">
  import { fade } from 'svelte/transition';
  import { transientStore } from '../stores/transientStore';
</script>

{#if $transientStore.visible}
  <div id="transient-message" class="transient-msg" role="status" aria-live="polite" in:fade={{ duration: 150 }} out:fade={{ duration: 100 }}>
    {$transientStore.message}<span class="ellipsis"></span>
  </div>
{/if}

<style>
  .transient-msg {
    position: relative;
    align-self: flex-start;
    padding: 0.5em 1em;

    --transient-text: var(--chat-text, #888);
    color: var(--transient-text);

    font-size: 1em;
  }

  .ellipsis::after {
    display: inline-block;
    width: 1.2em;
    content: '';
    animation: ellipsis-animation 1.5s steps(4, end) infinite;
  }

  @keyframes ellipsis-animation {
    0% {
      content: '';
    }
    25% {
      content: '.';
    }
    50% {
      content: '..';
    }
    75% {
      content: '...';
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .ellipsis::after {
      animation: none;
    }
  }
</style>
