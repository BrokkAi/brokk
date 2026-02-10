import {visit} from 'unist-util-visit';
import type {Root, Element, Parent, Text} from 'hast';

/**
 * Rehype plugin to detect tool-call blocks and annotate YAML code fences.
 *
 * Pattern (from ToolRegistry.getExplanationForToolRequest):
 *   <p><code>headline</code></p>
 *   <pre><code class="language-yaml">...</code></pre>
 *
 * Shiki replaces the original <pre> node with a fragment node of type "root" that
 * contains a single <pre>. After Shiki, the outer structure is:
 *   parent -> [ ... , paragraph, whitespace, fragment(root)[0]=<pre> ]
 *
 * This plugin:
 *   - Visits Shiki fragments first (so we have access to the outer parent and siblings).
 *   - Also supports the non-Shiki pipeline by handling plain <pre> nodes.
 *   - Annotates the inner <pre> with:
 *       data-tool-headline = headline
 *       data-collapse-default = "true"
 *   - Removes the preceding headline paragraph and any whitespace between it and the code block.
 */
export function rehypeToolCalls() {
  return (tree: Root) => {
    // Handle Shiki fragments that wrap the generated <pre> in a "root" node.
    visit(tree, isShikiPreFragment, (fragment: any, index: number, parent: Parent | undefined) => {
      if (!parent || index == null) return;

      const pre = (Array.isArray(fragment.children) ? fragment.children[0] : undefined) as Element | undefined;
      if (!pre || pre.type !== 'element' || pre.tagName !== 'pre') return;
      if (!isYamlPre(pre)) return;
      if (hasDataToolHeadline(pre)) return; // idempotency guard

      const found = findPreviousHeadline(parent, index);
      if (!found) return;

      annotatePreWithHeadline(pre, found.headline);

      // Remove the headline paragraph and all whitespace between it and the fragment.
      removeRange(parent, found.startIndex, index);
    });

    // Also support the non-Shiki pipeline where the <pre> is directly in the outer parent.
    visit(tree, (n: any) => n && n.type === 'element' && n.tagName === 'pre', (node: any, index: number, parent: Parent | undefined) => {
      if (!parent || index == null) return;

      // If this <pre> is the child of a Shiki fragment root, skip here (handled above).
      if (parent.type === 'root' && Array.isArray((parent as any).children) && (parent as any).children.length === 1) return;

      const pre = node as Element;
      if (!isYamlPre(pre)) return;
      if (hasDataToolHeadline(pre)) return; // idempotency guard

      const found = findPreviousHeadline(parent, index);
      if (!found) return;

      annotatePreWithHeadline(pre, found.headline);

      // Remove the headline paragraph and all whitespace between it and the <pre>.
      removeRange(parent, found.startIndex, index);
    });
  };
}

function annotatePreWithHeadline(pre: Element, headline: string): void {
  const props = (pre.properties ??= {});
  (props as any)['data-tool-headline'] = headline;
  (props as any)['data-collapse-default'] = 'true';
}

function isShikiPreFragment(n: any): boolean {
  if (!n || n.type !== 'root') return false;
  const children = (n as any).children;
  if (!Array.isArray(children) || children.length !== 1) return false;
  const first = children[0] as any;
  return first && first.type === 'element' && first.tagName === 'pre';
}

function isWhitespaceText(n: any): boolean {
  return n && n.type === 'text' && typeof n.value === 'string' && n.value.trim().length === 0;
}

/**
 * Finds the headline paragraph immediately preceding `uptoIndex`, skipping any
 * whitespace text nodes between the paragraph and the target index.
 *
 * Returns the headline text and the index where removal should start (the paragraph),
 * or undefined if not found.
 */
function findPreviousHeadline(parent: Parent, uptoIndex: number): { headline: string; startIndex: number } | undefined {
  let i = uptoIndex - 1;
  while (i >= 0 && isWhitespaceText((parent.children as any[])[i])) i--;
  if (i < 0) return undefined;

  const candidate = parent.children[i] as any;
  if (!candidate || candidate.type !== 'element' || candidate.tagName !== 'p') return undefined;

  const headline = extractSingleInlineCodeText(candidate as Element);
  if (!headline) return undefined;

  return { headline, startIndex: i };
}

function removeRange(parent: Parent, start: number, endExclusive: number): void {
  (parent.children as any[]).splice(start, endExclusive - start);
}

function isYamlPre(pre: Element): boolean {
  if (pre.type !== 'element' || pre.tagName !== 'pre') return false;

  // Shiki post-transform: <pre data-language="yaml">...</pre>
  const preLang = (pre.properties as any)?.['data-language'];
  if (preLang && String(preLang).toLowerCase() === 'yaml') return true;

  // Pre-Shiki shape: <pre><code class="language-yaml">...</code></pre>
  if (!Array.isArray(pre.children) || pre.children.length === 0) return false;
  const first = pre.children[0] as Element;
  if (!first || first.type !== 'element' || first.tagName !== 'code') return false;

  const className = (first.properties?.className ?? []) as unknown[];
  if (!Array.isArray(className)) return false;
  return className.some(c => String(c) === 'language-yaml');
}

function hasDataToolHeadline(pre: Element): boolean {
  const v = (pre.properties ?? {})['data-tool-headline'];
  return typeof v === 'string' && v.length > 0;
}

/**
 * Returns the text inside a paragraph if and only if it is a single inline <code> node
 * with a single text child. Otherwise returns undefined.
 */
function extractSingleInlineCodeText(p: Element): string | undefined {
  if (!Array.isArray(p.children) || p.children.length !== 1) return undefined;
  const only = p.children[0] as Element;
  if (!only || only.type !== 'element' || only.tagName !== 'code') return undefined;

  if (!Array.isArray(only.children) || only.children.length !== 1) return undefined;
  const t = only.children[0] as Text;
  if (!t || t.type !== 'text') return undefined;

  const value = (t.value ?? '').trim();
  return value.length > 0 ? value : undefined;
}
