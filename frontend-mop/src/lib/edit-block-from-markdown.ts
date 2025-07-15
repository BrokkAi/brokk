import type {FromMarkdownExtension} from 'mdast-util-from-markdown';
import {scanLines} from './edit-block-plugin';   // reuse existing, battle-tested logic

export const editBlockFromMarkdown: FromMarkdownExtension = {
  enter: {
    editBlock(token) {
      this.enter({ type: 'editBlock', raw: '' }, token);
    }
  },
  exit: {
    editBlock(token) {
      const node: any = this.stack[this.stack.length - 1];
      node.raw = this.sliceSerialize(token);
      const attr = token._attributes as { incomplete?: boolean; _info?: string } | undefined;

      // reuse scanLines() for filename / stats
      Object.assign(node, scanLines(node.raw.split('\n'), attr?._info ?? ''));

      node.incomplete = Boolean(attr?.incomplete);
      this.exit(token);
    }
  }
};
