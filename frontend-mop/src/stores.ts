import { writable } from 'svelte/store';
import type { SpinnerState } from './types';

export const themeStore = writable<boolean>(false);
export const spinnerStore = writable<SpinnerState>({ visible: false, message: '' });
