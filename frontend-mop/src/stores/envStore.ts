import { writable } from 'svelte/store';

export type EnvInfo = {
  version?: string;
  projectName?: string;
  nativeFileCount?: number;
  totalFileCount?: number;
  analyzerReady?: boolean;
  analyzerLanguages?: string | string[];
};

const initial: EnvInfo = {
  analyzerReady: false
};

export const envStore = writable<EnvInfo>(initial);

export function setEnvInfo(info: EnvInfo): void {
  envStore.set(info);
}
