import {writable} from "svelte/store";
import type {SpinnerState} from "@/types";

export const spinnerStore = writable<SpinnerState>({visible: false, message: ''});