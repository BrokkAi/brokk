import {diffLines} from 'diff';
import * as langMap from 'lang-map'; // Use 'lang-map' in JS without the asterisk


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


export function getMdLanguageTag(filename: string): string {
    // Extract the file extension (e.g., 'js' from 'foo.js')
    const ext = filename.split('.').pop()?.toLowerCase();
    if (!ext) {
        return '';
    }

    // Get the languages array for the extension
    const languages = langMap.languages(ext);

    // Return the first language if available (most extensions map to one)
    // If multiple, you could add logic to select based on context
    return languages?.[0] ?? '';
}
