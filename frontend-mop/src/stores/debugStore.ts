import {writable} from 'svelte/store';

// Debug flags store
interface DebugState {
    showCacheStats: boolean;
}

const initialState: DebugState = {
    showCacheStats: true
};

export const debugStore = writable<DebugState>(initialState);

// Helper functions for easy toggling
export function toggleCacheStats(): void {
    debugStore.update(state => ({
        ...state,
        showCacheStats: !state.showCacheStats
    }));
}

export function setCacheStatsVisible(visible: boolean): void {
    debugStore.update(state => ({
        ...state,
        showCacheStats: visible
    }));
}
