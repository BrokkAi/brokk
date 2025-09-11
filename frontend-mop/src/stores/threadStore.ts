import { writable } from 'svelte/store';

const { subscribe, update, set } = writable<Record<number, boolean>>({});

export const threadStore = {
    subscribe,
    toggleThread: (threadId: number): void => {
        update(state => {
            state[threadId] = !(state[threadId] ?? false);
            return state;
        });
    },
    setThreadCollapsed: (threadId: number, collapsed: boolean): void => {
        update(state => {
            state[threadId] = collapsed;
            return state;
        });
    },
    clearAll: (): void => {
        set({});
    }
};
