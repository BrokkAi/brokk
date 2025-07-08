import { unified } from 'unified';
import remarkParse from 'remark-parse';
import { editBlockPlugin } from './edit-block-plugin';
import { visit } from 'unist-util-visit';
import { expect, test } from 'vitest';

// Helper function to process markdown through the plugin and return the AST
function md2hast(md: string, { enableEditBlocks = true } = {}) {
  const processor = unified().use(remarkParse);
  if (enableEditBlocks) {
    processor.use(editBlockPlugin().remarkPlugin[0]);
  }
  return processor.runSync(processor.parse(md));
}

// Helper to find edit-block nodes in the AST
function findEditBlocks(tree: any): any[] {
  const editBlocks: any[] = [];
  visit(tree, (node) => {
    if (node.data && node.data.hName === 'edit-block') {
      editBlocks.push(node);
    }
  });
  return editBlocks;
}


test('detects unfenced edit block with inline filename', () => {
  const md = `
<<<<<<< SEARCH foo/bar.java
System.out.println("hi");
=======
System.out.println("bye");
>>>>>>> REPLACE
  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('foo/bar.java');
  expect(props.adds).toBe('1');
  expect(props.dels).toBe('1');
  expect(props.changed).toBe('1');
  expect(props.status).toBe('UNKNOWN');
});

test('detects unfenced edit block without filename', () => {
  const md = `
<<<<<<< SEARCH
old content line 1
old content line 2
=======
new content line 1
>>>>>>> REPLACE
  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('?');
  expect(props.adds).toBe('1');
  expect(props.dels).toBe('2');
  expect(props.changed).toBe('1');
});

test('ignores incomplete edit block', () => {
  const md = `
<<<<<<< SEARCH
some content
=======
  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(0);
});

test('handles empty search and replace sections', () => {
  const md = `
<<<<<<< SEARCH empty.txt
=======
=======
>>>>>>> REPLACE
  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('empty.txt');
  expect(props.adds).toBe('0');
  expect(props.dels).toBe('0');
  expect(props.changed).toBe('0');
});

test('detects edit block with code language as filename', () => {
  const md = `
<<<<<<< SEARCH java
public class Test {}
=======
public class Test {
    public static void main(String[] args) {
        System.out.println("Hello");
    }
}
>>>>>>> REPLACE
  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('java');
  expect(props.adds).toBe('4');
  expect(props.dels).toBe('1');
  expect(props.changed).toBe('1');
});

test('detects mixed content with edit blocks and code fences', () => {
  const md = `
Here's a list with mixed content:

- Item 1: normal text
- Item 2: with code
  \`\`\`java
  System.out.println("Item 2");
  \`\`\`
- Item 3: with edit block
  <<<<<<< SEARCH Test.java
  void test() {}
  ======= Test.java
  void test() { return; }
  >>>>>>> REPLACE Test.java
  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(1);

  const editProps = editBlocks[0].data.hProperties;
  expect(editProps.filename).toBe('Test.java');
  expect(editProps.adds).toBe('1');
  expect(editProps.dels).toBe('1');
  expect(editProps.changed).toBe('1');
  
  const codeProps = codeFences[0].data.hProperties;
  expect(codeProps.lang).toBe('java');
  expect(codeProps.content).toBe('System.out.println("Item 2");');
});

test('edit blocks are ignored when the feature flag is off', () => {
  const md = `
Regular text

<edit-block data-id="99" data-adds="1" data-dels="1" data-file="Foo.java"/>
  `;

  // Parse with the feature disabled
  const tree = md2hast(md, { enableEditBlocks: false });

  // No edit-block nodes should be present
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(0);
});

// ------------------------------------------------------------
// FENCED edit-block should be detected and parsed correctly
// (migrated from BrokkMarkdownExtensionTest.fencedBlockGetsRenderedAsEditBlock)
// ------------------------------------------------------------
test('detects fenced edit block', () => {
  // Markdown with a fenced edit-block
  const md = `
\`\`\`
foo.txt
<<<<<<< SEARCH
a
=======
b
>>>>>>> REPLACE
\`\`\`
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(1);

  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('foo.txt');
  expect(props.adds).toBe('1');
  expect(props.dels).toBe('1');
  expect(props.changed).toBe('1');
  expect(props.status).toBe('UNKNOWN');
});

// ------------------------------------------------------------
// UNFENCED edit-block should be detected and parsed correctly
// (migrated from BrokkMarkdownExtensionTest.unfencedBlockIsRecognisedEarly)
// ------------------------------------------------------------
test('detects unfenced edit block with filename', () => {
  const md = `
<<<<<<< SEARCH example.txt
lineA
=======
lineB
>>>>>>> REPLACE
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(1);

  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('example.txt');
  expect(props.adds).toBe('1');
  expect(props.dels).toBe('1');
  expect(props.changed).toBe('1');
  expect(props.status).toBe('UNKNOWN');
});

// ------------------------------------------------------------
// MULTIPLE FENCED edit-blocks should have distinct IDs
// (migrated from BrokkMarkdownExtensionTest.multipleBlocksReceiveDistinctIds)
// ------------------------------------------------------------
test('multiple fenced edit blocks have distinct ids', () => {
  const md = `
\`\`\`
file1.txt
<<<<<<< SEARCH
one
=======
two
>>>>>>> REPLACE
\`\`\`

\`\`\`
file2.txt
<<<<<<< SEARCH
three
=======
four
>>>>>>> REPLACE
\`\`\`
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(2);

  const ids = editBlocks.map(b => b.data.hProperties.id);
  expect(new Set(ids).size).toBe(2); // Ensure IDs are distinct
});

// ------------------------------------------------------------
// MULTIPLE UNFENCED edit-blocks should have distinct IDs
// (migrated from BrokkMarkdownExtensionTest.multipleBlocksWithoutFencesReceiveDistinctIds)
// ------------------------------------------------------------
test('multiple unfenced edit blocks have distinct ids', () => {
  const md = `
<<<<<<< SEARCH file1.txt
one
=======
two
>>>>>>> REPLACE

<<<<<<< SEARCH file2.txt
three
=======
four
>>>>>>> REPLACE
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(2);

  const ids = editBlocks.map(b => b.data.hProperties.id);
  expect(new Set(ids).size).toBe(2); // Ensure IDs are distinct
});
