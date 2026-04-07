/**
 * Web Worker for off-thread markdown rendering.
 * Receives { id, text, bubbleId } messages, responds with { id, html }.
 */
import { renderMarkdownFast } from "../markdown/processor";

const ctx = self as unknown as Worker;

ctx.onmessage = (e: MessageEvent<{ id: number; text: string; bubbleId: number }>) => {
  const { id, text, bubbleId } = e.data;
  try {
    const html = renderMarkdownFast(text, bubbleId);
    ctx.postMessage({ id, html });
  } catch (err) {
    ctx.postMessage({ id, html: escapeHtml(text) });
  }
};

function escapeHtml(str: string): string {
  return String(str ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
