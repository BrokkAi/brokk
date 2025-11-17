import { writable } from 'svelte/store';

let nextThreadId = 1;

export function getNextThreadId(): number {
    return nextThreadId++;
}

export type ThreadType = 'live' | 'history';

const threadTypes = new Map<number, ThreadType>();

const { subscribe, update } = writable<Record<number, boolean>>({});

export const threadStore = {
    subscribe,
    toggleThread: (threadId: number): void => {
        update(state => {
            state[threadId] = !(state[threadId] ?? false);
            return state;
        });
    },
    setThreadCollapsed: (threadId: number, collapsed: boolean, type?: ThreadType): void => {
        if (type) {
            threadTypes.set(threadId, type);
        }
        update(state => {
            state[threadId] = collapsed;
            return state;
        });
    },
    clearThreadsByType: (type: ThreadType): void => {
        const idsToClear: number[] = [];
        threadTypes.forEach((value, key) => {
            if (value === type) {
                idsToClear.push(key);
            }
        });

        if (idsToClear.length === 0) {
            return;
        }

        idsToClear.forEach(id => threadTypes.delete(id));

        update(state => {
            idsToClear.forEach(id => delete state[id]);
            return state;
        });
    },
};
