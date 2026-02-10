import { writable } from 'svelte/store';

export const historyCollapsedStore = writable<boolean>(true);
