<script lang="ts">
  import AIReasoningBubble from './AIReasoningBubble.svelte';
  import MessageBubble from './MessageBubble.svelte';
  import type { BubbleState } from '../types';

  export let bubbles: BubbleState[] = [];
</script>

{#each bubbles as bubble (bubble.seq)}
  {#if bubble.type === 'AI' && bubble.reasoning}
    <AIReasoningBubble {bubble} />
  {:else}
    <MessageBubble {bubble} />
  {/if}
{/each}
