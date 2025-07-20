/**
 * mdast build logic for edit-blocks.
 */
import { log } from './util';
import { nextEditBlockId } from './id-generator';

export function editBlockFromMarkdown() {
    return {
        enter: {
            // Create the node and remember it.
            editBlock(tok) {
                log('from-markdown', 'enter editBlock');
                const node = {
                    type: 'editBlock',
                    data: {
                        hName: 'edit-block',
                        hProperties: {
                            id: nextEditBlockId(),
                            filename: undefined as string | undefined,
                            search: undefined as string | undefined,
                            replace: undefined as string | undefined,
                            headerOk: false as boolean
                        }
                    }
                };
                this.enter(node, tok);
                this.data.currentEditBlock = node; // store a reference
            },

            // Filename
            editBlockFilename() {
                log('from-markdown', 'enter editBlockFilename');
            },

            // Search text
            editBlockSearchContent() {
                log('from-markdown', 'enter editBlockSearchContent');
                this.buffer(); // start collecting *search* text
            },

            // Replace text
            editBlockReplaceContent() {
                log('from-markdown', 'enter editBlockReplaceContent');
                this.buffer(); // start collecting *replace* text
            }
        },
        exit: {
            editBlockFilename(tok) {
                log('from-markdown', 'exit editBlockFilename');
                const node = this.data.currentEditBlock;
                node.data.hProperties.filename = this.sliceSerialize(tok);
            },

            editBlockSearchKeyword() {
                log('from-markdown', 'exit editBlockSearchKeyword');
                const node = this.data.currentEditBlock;
                node.data.hProperties.headerOk = true; // Mark header as complete
            },

            editBlockSearchContent(tok) {
                log('from-markdown', 'exit editBlockSearchContent');
                const node = this.data.currentEditBlock;
                node.data.hProperties.search = this.resume();
            },

            editBlockReplaceContent(tok) {
                log('from-markdown', 'exit editBlockReplaceContent');
                const node = this.data.currentEditBlock;
                node.data.hProperties.replace = this.resume();
            },

            editBlock(tok) {
                log('from-markdown', 'exit editBlock');
                log('from-markdown', this.data.currentEditBlock, true);
                delete this.data['currentEditBlock']; // clear helper
                this.exit(tok); // close the node
            }
        },
    };
}

