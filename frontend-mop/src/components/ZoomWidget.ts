/**
 * Zoom Widget Component (TypeScript)
 * Creates a floating zoom control with magnifying glass icon that expands to show font size controls
 */

import './ZoomWidget.css';

export function createZoomWidget(): HTMLElement {
    const widgetHTML = `
        <div class="zoom-widget">
            <div class="zoom-icon">
                <svg width="16" height="16" viewBox="0 -960 960 960" fill="currentColor">
                    <path d="M560-160v-520H360v-120h520v120H680v520H560Zm-360 0v-320H80v-120h360v120H320v320H200Z"/>
                </svg>
            </div>
            <div class="zoom-controls">
                <button class="zoom-btn" onclick="window.brokk.zoomOut()" title="Zoom Out (Ctrl/Cmd + -)">
                    <span class="zoom-widget-font-size small">A</span>
                </button>
                <button class="zoom-btn reset" onclick="window.brokk.resetZoom()" title="Reset Zoom (Ctrl/Cmd + 0)">
                    <span class="zoom-widget-font-size medium">A</span>
                </button>
                <button class="zoom-btn" onclick="window.brokk.zoomIn()" title="Zoom In (Ctrl/Cmd + +)">
                    <span class="zoom-widget-font-size large">A</span>
                </button>
            </div>
        </div>
    `;

    const container = document.createElement('div');
    container.innerHTML = widgetHTML;

    const first = container.firstElementChild as HTMLElement | null;
    if (!first) {
        throw new Error('Failed to create zoom widget');
    }
    return first;
}

export function addZoomWidgetToPage(): void {
    // Only add if not already present
    if (document.querySelector('.zoom-widget')) {
        return;
    }

    const widget = createZoomWidget();
    document.body.appendChild(widget);
}

// Auto-initialize when module is loaded in browser environment
if (typeof window !== 'undefined') {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', addZoomWidgetToPage, { once: true });
    } else {
        addZoomWidgetToPage();
    }
}