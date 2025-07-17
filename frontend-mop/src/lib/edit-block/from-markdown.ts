/**
 * mdast build logic for edit-blocks.
 */
import { log } from './util';

export function editBlockFromMarkdown() {
    return {
        enter: {
            // Create the node and remember it.
            editBlock(tok) {
                log('enter editBlock');
                const node = {
                    type: 'editBlock',
                    data: {
                        hName: 'edit-block',
                        hProperties: {
                            filename: undefined as string | undefined,
                            search: undefined as string | undefined,
                            replace: undefined as string | undefined
                        }
                    }
                };
                this.enter(node, tok);
                this.data.currentEditBlock = node; // store a reference
            },

            // Filename
            editBlockFilename() {
                log('enter editBlockFilename');
            },

            // Search text
            editBlockSearchContent() {
                log('enter editBlockSearchContent');
                this.buffer(); // start collecting *search* text
            },

            // Replace text
            editBlockReplaceContent() {
                log('enter editBlockReplaceContent');
                this.buffer(); // start collecting *replace* text
            }
        },
        exit: {
            editBlockFilename(tok) {
                log('exit editBlockFilename');
                const node = this.data.currentEditBlock;
                node.data.hProperties.filename = this.sliceSerialize(tok);
            },

            editBlockSearchContent(tok) {
                log('exit editBlockSearchContent');
                const node = this.data.currentEditBlock;
                node.data.hProperties.search = this.resume();
            },

            editBlockReplaceContent(tok) {
                log('exit editBlockReplaceContent');
                const node = this.data.currentEditBlock;
                node.data.hProperties.replace = this.resume();
            },

            editBlock(tok) {
                log('exit editBlock');
                log(this.data.currentEditBlock, true);
                delete this.data['currentEditBlock']; // clear helper
                this.exit(tok); // close the node
            }
        },
    };
}

