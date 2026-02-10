import { writable } from 'svelte/store';

export interface TransientState {
    visible: boolean;
    message: string;
}

const initialState: TransientState = {
    visible: false,
    message: '',
};

function createTransientStore() {
    const { subscribe, set } = writable<TransientState>(initialState);

    function show(message = '') {
        set({
            visible: true,
            message
        });
    }

    function hide() {
        set({
            visible: false,
            message: ''
        });
    }

    return {
        subscribe,
        show,
        hide
    };
}

/**
 * Singleton store for transient messages.
 * Transient messages are displayed temporarily without persisting to history.
 */
export const transientStore = createTransientStore();

/**
 * Show a transient message (compat wrapper).
 */
export function showTransientMessage(message: string): void {
    transientStore.show(message);
}

/**
 * Hide the transient message (compat wrapper).
 */
export function hideTransientMessage(): void {
    transientStore.hide();
}
