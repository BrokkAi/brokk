import { type Change, diffLines } from 'diff';

export interface UnifiedDiff {
  text: string;
  added: number[];
  removed: number[];
}

export function buildUnifiedDiff(search: string, replace: string): UnifiedDiff {
  const diff = diffLines(search, replace);
  const result = {
    text: '',
    added: [] as number[],
    removed: [] as number[]
  };
  const lines: string[] = [];
  let currentLine = 0;

  for (const part of diff) {
    const partLines = part.value.split('\n');
    const prefix = part.added ? '+' : part.removed ? '-' : ' ';
    const bucket = part.added ? result.added : part.removed ? result.removed : null;

    for (let i = 0; i < partLines.length; i++) {
      const line = partLines[i];
      // The diff lib often includes a final empty string if the part ends with a newline.
      // We don't want to render this as an extra line in the diff.
      if (line === '' && i === partLines.length - 1 && partLines.length > 1) continue;

      currentLine++;
      lines.push(prefix + line);
      if (bucket) {
        bucket.push(currentLine);
      }
    }
  }

  result.text = lines.join('\n');
  return result;
}

/**
 * Quick heuristic to get a language id for Shiki from a filename.
 */
export function detectLang(filename: string | undefined): string {
  if (!filename) return 'text';
  const ext = filename.split('.').pop()?.toLowerCase() ?? '';
  const langMap: Record<string, string> = {
    js: 'javascript', ts: 'typescript', c: 'c', h: 'c', cpp: 'cpp',
    java: 'java', py: 'python', kt: 'kotlin', sh: 'bash',
    json: 'json', yml: 'yaml', yaml: 'yaml', md: 'markdown',
    svelte: 'svelte', html: 'html', css: 'css', scss: 'scss',
    gradle: 'groovy', pom: 'xml'
  };
  return langMap[ext] ?? 'text';
}
