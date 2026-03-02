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

    it('should only show messages from the latest replay when called multiple times rapidly', async () => {
        // Set A: 5 messages
        const setA = Array.from({ length: 5 }, (_, i) => ({
            sequence: i,
            isCompressed: false,
            messages: [{ role: 'user', text: `Set A - ${i}` }]
        }));

        // Set B: 3 messages
        const setB = Array.from({ length: 3 }, (_, i) => ({
            sequence: i,
            isCompressed: false,
            messages: [{ role: 'user', text: `Set B - ${i}` }]
        }));

        // Call A immediately followed by B (synchronously)
        replayConversation(setA);
        replayConversation(setB);

        // Wait for all async chunks to flush
        await flushMacrotasks();

        // Check the final message count and content
        // Without the generation check, Set A chunks would still execute and append to the list.
        const messageTexts = Array.from(messagesEl.querySelectorAll('.message-content'))
            .map(el => el.textContent);

        expect(messageTexts).to.have.lengthOf(3, "Should only have messages from the most recent set.");
        expect(messageTexts.every(t => t.startsWith("Set B"))).to.be.true;
        expect(messageTexts).to.not.any.satisfy(t => t.startsWith("Set A"));
    });
});
