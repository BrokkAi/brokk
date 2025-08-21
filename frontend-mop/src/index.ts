import './styles/global.scss';
import {mount, tick} from 'svelte';
import Mop from './MOP.svelte';
import {bubblesStore, onBrokkEvent} from './stores/bubblesStore';
import {spinnerStore} from './stores/spinnerStore';
import {themeStore} from './stores/themeStore';
import {createSearchController, type SearchController} from './search/search';

let searchCtrl: SearchController | null = null;

 // Initialization calls at the top
checkWorkerSupport();
initializeApp();
const buffer = setupBrokkInterface();
replayBufferedItems(buffer);
void initSearchController();
setupSearchRehighlight();

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
        getSearchState: () => searchCtrl?.getState()
    };
    return buffer;
}

async function handleEvent(payload: any): Promise<void> {
    onBrokkEvent(payload); // updates store & talks to worker

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
}

function setAppTheme(dark: boolean): void {
    themeStore.set(dark);
    const html = document.querySelector('html')!;
    const [addTheme, removeTheme] = dark ? ['theme-dark', 'theme-light'] : ['theme-light', 'theme-dark'];
    html.classList.add(addTheme);
    html.classList.remove(removeTheme);
}

function showSpinnerMessage(message = ''): void {
    spinnerStore.show(message);
}

function hideSpinnerMessage(): void {
    spinnerStore.hide();
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
    bubblesStore.subscribe(() => {
        if (!searchCtrl || !searchCtrl.getState().query) return;
        if (pending) return;
        pending = true;

        tick().then(() => {
            requestAnimationFrame(() => {
                pending = false;
                searchCtrl?.onContentChanged();
            });
        });
    });
}
