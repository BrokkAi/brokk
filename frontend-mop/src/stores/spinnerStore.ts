import {writable} from "svelte/store";
import type {SpinnerState} from "../types";

function createSpinnerStore() {
    const {subscribe, set} = writable<SpinnerState>({visible: false, message: ''});

    let timerId: number | null = null;
    let baseMessage = '';
    let startTime = 0;

    function stopTimer() {
        if (timerId !== null) {
            clearInterval(timerId);
            timerId = null;
        }
    }

    function show(message = '') {
        stopTimer();
        baseMessage = message;
        startTime = Date.now();

        const updateMessage = () => {
            const elapsed = Math.floor((Date.now() - startTime) / 1000);
            const fullMessage = baseMessage ? `${baseMessage} (${elapsed}s)` : `(${elapsed}s)`;
            set({
                visible: true,
                message: fullMessage
            });
        };

        updateMessage(); // Show immediately with (0s)
        timerId = window.setInterval(updateMessage, 1000);
    }

    function hide() {
        stopTimer();
        set({visible: false, message: ''});
    }

    return {
        subscribe,
        show,
        hide
    };
}

export const spinnerStore = createSpinnerStore();
