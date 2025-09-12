import { writable } from 'svelte/store';

export const MIN_ZOOM = 0.5;
export const MAX_ZOOM = 2.0;
export const ZOOM_STEP = 0.2;

export const zoomStore = writable(1.0);

export function zoomIn(): void {
    zoomStore.update(current => Math.min(current + ZOOM_STEP, MAX_ZOOM));
}

export function zoomOut(): void {
    zoomStore.update(current => Math.max(current - ZOOM_STEP, MIN_ZOOM));
}

export function setZoom(value: number): void {
    const clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    zoomStore.set(clamped);
}

export function resetZoom(): void {
    zoomStore.set(1.0);
}

export function getZoomPercentage(zoom: number): string {
    return Math.round(zoom * 100) + '%';
}
