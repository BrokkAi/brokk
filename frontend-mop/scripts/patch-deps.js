/**
 * Patches micromark-util-subtokenize to avoid an infinite loop when a self-identity jump is created
 * during streaming subtokenization (slice length === 1).
 *
 * This script mirrors the Gradle frontendPatch and should be run before vite dev.
 */
const fs = require('fs');
const path = require('path');

function patchFile(file, transform) {
  if (!fs.existsSync(file)) {
    console.warn('[patch-deps] skip, not found:', file);
    return;
  }
  let content = fs.readFileSync(file, 'utf8');

  const originalContent = content;

  // 1) Guard while(index in jumps) against identity mapping
  content = content.replace(
    /while\s*\(\s*index\s+in\s+jumps\s*\)\s*\{\s*index\s*=\s*jumps\s*\[\s*index\s*]\s*;\s*}/m,
    [
      'while (index in jumps) {',
      '  const next = jumps[index];',
      '  if (next === index) { index++; break; }',
      '  index = next;',
      '}'
    ].join('\n')
  );

  // 2) Prevent creating identity jumps when slice.length === 1 in subcontent()
  content = content.replace(
    /jumps\.push\(\s*\[\s*start\s*,\s*start\s*\+\s*slice\.length\s*-\s*1\s*]\s*\)\s*;/m,
    [
      'const end = start + slice.length - 1;',
      'if (end > start) jumps.push([start, end]);'
    ].join('\n')
  );

  if (content !== originalContent) {
    fs.writeFileSync(file, content, 'utf8');
    console.log('[patch-deps] patched', file);
  } else {
    console.log('[patch-deps] no changes applied to', file, '(patterns not matched)');
  }
}

(function main() {
  const projectRoot = path.resolve(__dirname, '..');
  const target = path.join(projectRoot, 'node_modules', 'micromark-util-subtokenize', 'index.js');
  patchFile(target);
})();
