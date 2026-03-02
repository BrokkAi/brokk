import { expect } from 'chai';
import { replayConversation } from './chat.js';

describe('Chat WebView Regression Test', () => {
    let messagesEl;

    beforeEach(() => {
        // Set up DOM environment
        document.body.innerHTML = `
            <div id="messages" style="height: 500px; overflow: auto;"></div>
            <div id="welcome"></div>
            <button id="submit-btn"></button>
            <button id="cancel-btn"></button>
            <div id="status-bar"></div>
        `;
        messagesEl = document.getElementById('messages');
        
        // Mock scroll properties since jsdom doesn't perform layout
        // We simulate a container where scrollHeight grows as children are added
        Object.defineProperty(messagesEl, 'clientHeight', { value: 500, configurable: true });
        let currentScrollTop = 0;
        Object.defineProperty(messagesEl, 'scrollTop', {
            get: () => currentScrollTop,
            set: (v) => { currentScrollTop = v; },
            configurable: true
        });

        // Mock requestAnimationFrame to execute immediately
        global.requestAnimationFrame = (cb) => {
            cb();
            return 0;
        };
    });

    afterEach(() => {
        delete global.requestAnimationFrame;
    });

    /** Helper to flush the setTimeout(..., 0) queue used by chunking */
    async function flushMacrotasks() {
        for (let i = 0; i < 5; i++) {
            await new Promise(resolve => setTimeout(resolve, 0));
        }
    }

    it('should scroll to bottom after replaying a long conversation', async () => {
        // 1. Create 30 entries (exceeds CHUNK_SIZE of 20)
        const entries = Array.from({ length: 30 }, (_, i) => ({
            sequence: i,
            isCompressed: false,
            messages: [{ role: 'user', text: `Message ${i}` }]
        }));

        // Mock scrollHeight to simulate content exceeding clientHeight
        // In a real DOM, this would happen naturally as messages are appended.
        Object.defineProperty(messagesEl, 'scrollHeight', {
            get: () => messagesEl.childElementCount * 100,
            configurable: true
        });

        // 2. Execute replay
        replayConversation(entries);

        // 3. Wait for all chunks to process via setTimeout(..., 0)
        await flushMacrotasks();

        // 4. Assert scroll position
        // scrollHeight (30 * 100 = 3000) - clientHeight (500) = 2500
        const expectedMinScroll = messagesEl.scrollHeight - messagesEl.clientHeight;
        
        expect(messagesEl.scrollTop).to.be.at.least(expectedMinScroll, 
            "The view should have scrolled to the bottom after the final chunk was rendered.");
    });
});
