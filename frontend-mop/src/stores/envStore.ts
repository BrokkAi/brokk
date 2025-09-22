import { writable } from 'svelte/store';

export type EnvInfo = {
  version?: string;
  projectName?: string;
  nativeFileCount?: number;
  totalFileCount?: number;
  analyzerReady?: boolean;
  analyzerStatus?: string;
  analyzerLanguages?: string | string[];
};

const initial: EnvInfo = {
  analyzerReady: false,
  analyzerStatus: 'Initializing...'
};

export const envStore = writable<EnvInfo>(initial);

export function setEnvInfo(info: EnvInfo): void {
  envStore.set(info);
}
