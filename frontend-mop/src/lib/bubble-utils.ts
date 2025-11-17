import type { Bubble } from '../types';

type BubbleDisplayDefaults = {
    title: string;
    iconId: string;
    hlVar: string;
    bgVar: string;
};

// The 'CUSTOM' type is handled in MessageBubble.svelte, but not in the Bubble type definition.
// We'll support it here to centralize the logic.
type ExtendedBubbleType = Bubble['type'] | 'CUSTOM';

export function getBubbleDisplayDefaults(type: ExtendedBubbleType): BubbleDisplayDefaults {
  switch (type) {
    case 'USER':
      return {
        title: 'You',
        iconId: 'mdi:account',
        hlVar: '--message-border-user',
        bgVar: '--message-background',
      };
    case 'AI':
      return {
        title: 'Brokk',
        iconId: 'mdi:robot',
        hlVar: '--message-border-ai',
        bgVar: '--message-background',
      };
    case 'SYSTEM':
      return {
        title: 'System',
        iconId: 'mdi:cog',
        hlVar: '--message-border-custom',
        bgVar: '--message-background',
      };
    case 'CUSTOM':
      return {
        title: 'Custom',
        iconId: 'mdi:wrench',
        hlVar: '--message-border-custom',
        bgVar: '--custom-message-background',
      };
    default:
        // Fallback for any unknown type
        return {
            title: 'Message',
            iconId: 'mdi:message',
            hlVar: '--message-border-custom',
            bgVar: '--message-background',
        };
  }
}
