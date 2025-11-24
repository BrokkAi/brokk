import { writable } from 'svelte/store';

type ViewMode = 'messages' | 'summary';

const { subscribe, update } = writable<Record<number, ViewMode>>({});

export const summaryViewStore = {
    subscribe,
    /**
     * Toggle the view mode for a given thread between 'messages' and 'summary'.
     * If the mode is not set, defaults to 'messages', so toggle sets it to 'summary'.
     */
    toggleViewMode: (threadId: number): void => {
        update(state => {
            const currentMode = state[threadId] ?? 'messages';
            state[threadId] = currentMode === 'messages' ? 'summary' : 'messages';
            return state;
        });
    },
    /**
     * Set the view mode for a given thread to a specific mode.
     */
    setViewMode: (threadId: number, mode: ViewMode): void => {
        update(state => {
            state[threadId] = mode;
            return state;
        });
    },
    /**
     * Get the view mode for a given thread (defaults to 'messages' if not set).
     * Intended for use with derived stores or imperative checks.
     */
    getViewMode: (threadId: number): ViewMode => {
        let result: ViewMode = 'messages';
        const unsubscribe = subscribe(state => {
            result = state[threadId] ?? 'messages';
        });
        unsubscribe();
        return result;
    },
    /**
     * Clear view modes for a given type (e.g., 'history').
     * Mirrors threadStore.clearThreadsByType behavior.
     */
    clearViewModesByThreadIds: (threadIds: number[]): void => {
        update(state => {
            threadIds.forEach(id => delete state[id]);
            return state;
        });
    },
};
