import { writable } from 'svelte/store';

export type LanguageInfo = {
  name: string;
  fileCount: number;
  depCount: number;
};

export type EnvInfo = {
  version?: string;
  projectName?: string;
  totalFileCount?: number;
  analyzerReady?: boolean;
  analyzerLanguages?: LanguageInfo[];
  isSimplifiedInstructionsPanel?: boolean;
};

const initial: EnvInfo = {
  analyzerReady: false
};

export const envStore = writable<EnvInfo>(initial);

export function setEnvInfo(info: EnvInfo): void {
  envStore.set(info);
}