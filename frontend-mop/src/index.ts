import './styles/global.scss';
import {mount, tick} from 'svelte';
import {get} from 'svelte/store';
import Mop from './MOP.svelte';
import {bubblesStore, onBrokkEvent} from './stores/bubblesStore';
import {onHistoryEvent} from './stores/historyStore';
import {spinnerStore} from './stores/spinnerStore';
import {themeStore} from './stores/themeStore';
import { threadStore } from './stores/threadStore';
import {createSearchController, type SearchController} from './search/search';
import {reparseAll} from './stores/bubblesStore';
import {log, createLogger} from './lib/logging';
import {onSymbolResolutionResponse, clearSymbolCache} from './stores/symbolCacheStore';
import {zoomIn, zoomOut, resetZoom, zoomStore, getZoomPercentage, setZoom} from './stores/zoomStore';
import './components/ZoomWidget.ts';

const mainLog = createLogger('main');

let searchCtrl: SearchController | null = null;

 // Initialization calls at the top
checkWorkerSupport();
initializeApp();
const buffer = setupBrokkInterface();
replayBufferedItems(buffer);
void initSearchController();
setupSearchRehighlight();
setupZoomDisplayObserver();

// Function definitions below
function checkWorkerSupport(): void {
    if (!('Worker' in window)) {
        alert('This version of Brokk requires a newer runtime with Web Worker support.');
        throw new Error('Web Workers unsupported');
    }
}

function initializeApp(): void {
    mount(Mop, {
        target: document.getElementById('mop-root')!,
        props: {bubblesStore, spinnerStore}
    } as any);

    // Set initial production class on body for dev mode detection
    const isProduction = !import.meta.env.DEV;
    document.body.classList.toggle('production', isProduction);
}

function setupBrokkInterface(): any[] {
    // Replace the temporary brokk proxy with the real implementation
    const buffer = window.brokk._buffer;
    window.brokk = {
        _buffer: [],
        onEvent: handleEvent,
        getSelection: getCurrentSelection,
        clear: clearChat,
        setTheme: setAppTheme,
        showSpinner: showSpinnerMessage,
        hideSpinner: hideSpinnerMessage,

        // Search API
        setSearch: (query: string, caseSensitive: boolean) => searchCtrl?.setQuery(query, caseSensitive),
        clearSearch: () => searchCtrl?.clear(),
        nextMatch: () => searchCtrl?.next(),
        prevMatch: () => searchCtrl?.prev(),
        scrollToCurrent: () => searchCtrl?.scrollCurrent(),
        getSearchState: () => searchCtrl?.getState(),

        // Symbol lookup API
        refreshSymbolLookup: refreshSymbolLookup,
        onSymbolLookupResponse: onSymbolResolutionResponse,

        // Zoom API
        zoomIn: () => {
            zoomIn();
        },
        zoomOut: () => {
            zoomOut();
        },
        resetZoom: () => {
            resetZoom();
        },
        setZoom: (value: number) => {
            setZoom(value);
        },

        // Debug API
        toggleWrapStatus: () => typeof window !== 'undefined' && window.toggleWrapStatus ? window.toggleWrapStatus() : undefined,

    };

    // Signal to Java that the bridge is ready
    if (window.javaBridge && window.javaBridge.onBridgeReady) {
        window.javaBridge.onBridgeReady();
    }

    return buffer;
}

async function handleEvent(payload: any): Promise<void> {
    if (payload.type === 'history-reset' || payload.type === 'history-task') {
        onHistoryEvent(payload);
    } else {
        onBrokkEvent(payload); // updates store & talks to worker
    }

    // Wait until Svelte updated *and* browser painted
    await tick();
    requestAnimationFrame(() => {
        if (payload.epoch) window.javaBridge?.onAck(payload.epoch);
    });
}

function getCurrentSelection(): string {
    return window.getSelection()?.toString() ?? '';
}

function clearChat(): void {
    onBrokkEvent({type: 'clear', epoch: 0});
    onHistoryEvent({type: 'history-reset', epoch: 0});
}

function setAppTheme(dark: boolean, isDevMode?: boolean, wrapMode?: boolean, zoom?: number): void {
    console.info('setTheme executed: dark=' + dark + ', isDevMode=' + isDevMode + ', wrapMode=' + wrapMode + ', zoom=' + zoom);
    themeStore.set(dark);
    const html = document.querySelector('html')!;

    // Handle theme classes
    const [addTheme, removeTheme] = dark ? ['theme-dark', 'theme-light'] : ['theme-light', 'theme-dark'];
    html.classList.add(addTheme);
    html.classList.remove(removeTheme);

    // Set zoom if provided
    if (zoom !== undefined) {
        setZoom(zoom);
    }

    // Handle wrap mode classes - default to wrap mode enabled
    const shouldWrap = wrapMode !== undefined ? wrapMode : true;
    if (shouldWrap) {
        html.classList.add('code-wrap-mode');
        console.info('Applied code-wrap-mode class');
    } else {
        html.classList.remove('code-wrap-mode');
        console.info('Removed code-wrap-mode class');
    }

    // Trigger status update for debug display
    if (typeof window !== 'undefined' && window.updateWrapStatus) {
        window.updateWrapStatus();
    }

    // Determine production mode: use Java's isDevMode if provided, otherwise fall back to frontend detection
    mainLog.info(`set theme dark: ${dark} dev mode: ${isDevMode}`);
    let isProduction: boolean;
    if (isDevMode !== undefined) {
        // Java explicitly told us dev mode status
        isProduction = !isDevMode;
    } else {
        // Fall back to frontend-only detection (for compatibility)
        isProduction = !import.meta.env.DEV;
    }
    document.body.classList.toggle('production', isProduction);
}

function showSpinnerMessage(message = ''): void {
    spinnerStore.show(message);
}

function hideSpinnerMessage(): void {
    spinnerStore.hide();
}

/**
 * Generic symbol refresh mechanism that clears cache and triggers UI refresh.
 * Can be called from multiple scenarios: analyzer ready, context switch,
 * manual refresh, configuration changes, error recovery, etc.
 *
 * @param contextId - The context ID to refresh symbols for (defaults to 'main-context')
 */
function refreshSymbolLookup(contextId: string = 'main-context'): void {
    mainLog.debug(`[symbol-refresh] Refreshing symbols for context: ${contextId}, clearing cache and triggering UI refresh`);

    // Clear symbol cache to ensure fresh lookups
    clearSymbolCache(contextId);

    // Trigger symbol lookup for visible symbols to highlight them
    reparseAll(contextId);
}


function replayBufferedItems(buffer: any[]): void {
    // Replay buffered calls and events in sequence order
    if (buffer.length > 0) {
        console.log('Replaying', buffer.length, 'buffered items');
        buffer.sort((a, b) => a.seq - b.seq).forEach(item => {
            if (item.type === 'event' && item.payload) {
                console.log('Replaying event with epoch:', JSON.stringify(item.payload));
                window.brokk.onEvent(item.payload);
            } else if (item.type === 'call' && item.method) {
                console.log('Replaying call to', item.method, 'with args:', item.args);
                const brokk = window.brokk as Record<string, (...args: unknown[]) => unknown>;
                if (typeof brokk[item.method] === 'function') {
                    brokk[item.method](...(item.args ?? []));
                } else {
                    console.warn('Method', item.method, 'no longer exists; skipping replay');
                }
            }
        });
    }
}

async function initSearchController(): Promise<void> {
    await tick();
    const container = document.getElementById('chat-container') ?? document.getElementById('mop-root')!;
    if (!container) {
        console.warn('[search] container not found');
        return;
    }
    searchCtrl = createSearchController(container);
}

function setupSearchRehighlight(): void {
    let pending = false;
    const trigger = () => {
        if (!searchCtrl || !searchCtrl.getState().query) return;
        if (pending) return;
        pending = true;

        tick().then(() => {
            requestAnimationFrame(() => {
                pending = false;
                searchCtrl?.onContentChanged();
            });
        });
    };
    bubblesStore.subscribe(trigger);
    threadStore.subscribe(trigger);
}

function setupZoomDisplayObserver(): void {
    const render = (zoom: number) => {
        const el = document.getElementById('zoom-display');
        if (el) {
            el.textContent = getZoomPercentage(zoom);
        }
    };

    // Initial render and ongoing updates
    render(get(zoomStore));
    zoomStore.subscribe((zoom) => {
        render(zoom);
        try {
            (window as any).javaBridge?.onZoomChanged?.(zoom);
        } catch (e) {
            // ignore when bridge not ready or in dev
        }
    });
}
