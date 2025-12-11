import {writable} from 'svelte/store';

export interface TransientState {
    visible: boolean;
    message: string;
}

const initialState: TransientState = {
    visible: false,
    message: '',
};

/**
 * Singleton store for transient messages.
 * Transient messages are displayed temporarily without persisting to history.
 */
export const transientStore = writable<TransientState>(initialState);

/**
 * Show a transient message.
 * @param message - The message text to display
 */
export function showTransientMessage(message: string): void {
    transientStore.set({
        visible: true,
        message,
    });
}

/**
 * Hide the transient message.
 */
export function hideTransientMessage(): void {
    transientStore.set({
        visible: false,
        message: '',
    });
}
