/**
 * mdast build logic for edit-blocks.
 */
import type {EditBlockProperties} from '../../worker/shared';
import { log } from './util';
import {bubbleId, nextEditBlockId} from './id-generator';
import { parseGitDiffContent } from './tokenizer/git-diff-parser';

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
                            bubbleId: bubbleId(),
                            id: nextEditBlockId(),
                            isExpanded: false,
                            filename: undefined,
                            search: undefined,
                            replace: undefined,
                            headerOk: false
                        } as EditBlockProperties
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
            },

            // Git diff content
            gitDiffContent() {
                log('from-markdown', 'enter gitDiffContent');
                this.buffer(); // start collecting git diff text
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

            gitDiffContent(tok) {
                log('from-markdown', 'exit gitDiffContent');
                // Get the current edit block node from the stack or data
                let node = this.data.currentEditBlock;
                if (!node) {
                    // If currentEditBlock not set yet, get it from the stack
                    node = this.stack[this.stack.length - 1];
                }

                // Git diff content is extracted and parsed here
                const content = this.resume();

                try {
                    const result = parseGitDiffContent(content);

                    node.data.hProperties.filename = result.filename;
                    node.data.hProperties.search = result.beforeContent;
                    node.data.hProperties.replace = result.afterContent;
                    node.data.hProperties.headerOk = true;
                    node.data.hProperties.isGitDiff = true; // Mark as git diff format
                } catch (e) {
                    // On error, set minimal properties to avoid further issues
                    if (node && node.data && node.data.hProperties) {
                        node.data.hProperties.filename = '?';
                        node.data.hProperties.search = '';
                        node.data.hProperties.replace = '';
                        node.data.hProperties.headerOk = false;
                    }
                }
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
