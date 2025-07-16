/**
 * mdast build logic for edit-blocks.
 */
export function editBlockFromMarkdown() {
    return {
        enter: {
            // Create the node and remember it.
            editBlock(tok) {
                console.log('enter editBlock');
                const node = {
                    type: 'editBlock',
                    data: {
                        filename: undefined as string | undefined,
                        search: undefined as string | undefined,
                        replace: undefined as string | undefined
                    }
                };
                this.enter(node, tok);
                this.data.currentEditBlock = node; // store a reference
            },

            // Filename
            editBlockFilename() {
                console.log('enter editBlockFilename');
                // this.buffer(); // start collecting raw filename
            },

            // Search text
            editBlockSearchContent() {
                console.log('enter editBlockSearchContent');
                this.buffer(); // start collecting *search* text
            },

            // Replace text
            editBlockReplaceContent() {
                console.log('enter editBlockReplaceContent');
                this.buffer(); // start collecting *replace* text
            }
        },
        exit: {
            editBlockFilename(tok) {
                console.log('exit editBlockFilename');
                const node = this.data.currentEditBlock;
                node.data.filename = this.sliceSerialize(tok);
            },

            editBlockSearchContent(tok) {
                console.log('exit editBlockSearchContent');
                const node = this.data.currentEditBlock;
                node.data.search = this.resume();
            },

            editBlockReplaceContent(tok) {
                console.log('exit editBlockReplaceContent');
                const node = this.data.currentEditBlock;
                node.data.replace = this.resume();
            },

            editBlock(tok) {
                console.log('exit editBlock');
                delete this.data['currentEditBlock']; // clear helper
                this.exit(tok); // close the node
            }
        },
    };
}
