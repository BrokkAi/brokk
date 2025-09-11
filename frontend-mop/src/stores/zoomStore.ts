import { writable } from 'svelte/store';

export const zoomStore = writable(1.0);

export function zoomIn(): void {
    zoomStore.update(current => Math.min(current + 0.2, 2.0));
}

export function zoomOut(): void {
    zoomStore.update(current => Math.max(current - 0.2, 0.5));
}

export function resetZoom(): void {
    zoomStore.set(1.0);
}

export function getZoomPercentage(zoom: number): string {
    return Math.round(zoom * 100) + '%';
}