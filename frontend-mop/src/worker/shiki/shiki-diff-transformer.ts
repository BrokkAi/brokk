export const transformerDiffLines = (added: number[], removed: number[]) => ({
  name: 'diff-lines',
  line(node, lineNo) {
    if (added.includes(lineNo)) {
      this.addClassToHast(node, 'diff-line diff-add');
    } else if (removed.includes(lineNo)) {
      this.addClassToHast(node, 'diff-line diff-del');
    }
  },
  pre(node) {
    if (added.length || removed.length) {
      this.addClassToHast(node, 'has-diff');
    }
  }
});
