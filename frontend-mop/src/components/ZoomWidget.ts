/**
 * Zoom Widget Component (TypeScript)
 * Creates a floating zoom control with magnifying glass icon that expands to show font size controls
 */

import './ZoomWidget.css';

export function createZoomWidget(): HTMLElement {
    const widgetHTML = `
        <div class="zoom-widget">
            <div class="zoom-icon">
                <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
                    <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z"></path>
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