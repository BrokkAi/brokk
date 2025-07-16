import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';
import { remarkEditBlock } from '../lib/edit-block-plugin';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import { visit } from 'unist-util-visit';
import { expect, test } from 'vitest';
import { gfmEditBlock, editBlockFromMarkdown } from './micromark-edit-block';

// Helper function to process markdown through the plugin and return the AST
function md2hast(md: string, { enableEditBlocks = true } = {}) {
  const processor = unified().use(remarkParse);
  if (enableEditBlocks) {
    processor
      .data('micromarkExtensions', [gfmEditBlock()])
      .data('fromMarkdownExtensions', [editBlockFromMarkdown()])
      .use(remarkEditBlock);
  }
  // Pass the markdown content as part of the VFile to ensure file.contents is available
  return processor.runSync(processor.parse(md), { value: md });
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

test('detects multiple unfenced edit block with inline filename', () => {
  const md = `
<<<<<<< SEARCH foo/bar.java
System.out.println("hi");
=======
System.out.println("bye");
>>>>>>> REPLACE

<<<<<<< SEARCH foo/bar.java
System.out.println("hi2");
=======
System.out.println("bye2");
>>>>>>> REPLACE

  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(2);
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

test('handles incomplete edit block', () => {
  const md = `
<<<<<<< SEARCH
some content
=======
  `;
  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('?');
  expect(props.search).toBe('some content\n');
  expect(props.replace).toBe('');
  expect(props.adds).toBe('0');
  expect(props.dels).toBe('1');
  expect(props.changed).toBe('0');
});

test('handles empty search and replace sections', () => {
  const md = `
<<<<<<< SEARCH empty.txt
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
  expect(props.adds).toBe('5');
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

test('detects multiple fenced edit block', () => {
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
\`\`\`
foo.txt
<<<<<<< SEARCH
a2
=======
b2
>>>>>>> REPLACE
\`\`\`
  `;

    const tree = md2hast(md);
    const editBlocks = findEditBlocks(tree);

    expect(editBlocks.length).toBe(2);
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

// ------------------------------------------------------------
// FILENAME only in fence
// ------------------------------------------------------------
test('detects filename only in fence', () => {
  const md = `
\`\`\`script.js
<<<<<<< SEARCH
old code
=======
new code
>>>>>>> REPLACE
\`\`\`
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('script.js');
});

test('detects edit blocks with conflict syntax', () => {
  const md = `
  Now I need to modify \`GitLogTab.java\` to use the new \`GitCommitBrowserPanel\` instead of the inline code:
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
      // Commit Browser Components (to be extracted)
      private JTable commitsTable;
      private DefaultTableModel commitsTableModel;
      private JTree changesTree;
      private DefaultTreeModel changesTreeModel;
      private DefaultMutableTreeNode changesRootNode;
      private JLabel revisionTextLabel; // For "Revision:" or "Revisions:"
      private JTextArea revisionIdTextArea; // For the actual commit ID(s)
      private JTextField commitSearchTextField;
  
      // Commit Browser Context Menu Items (to be extracted)
      private JMenuItem addToContextItem;
      private JMenuItem softResetItem;
      private JMenuItem revertCommitItem;
      private JMenuItem viewChangesItem;
      private JMenuItem compareAllToLocalItem;
      private JMenuItem popStashCommitItem;
      private JMenuItem applyStashCommitItem;
      private JMenuItem dropStashCommitItem;
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
      // Commit Browser Component
      private GitCommitBrowserPanel commitBrowserPanel;
      private JTextField commitSearchTextField;
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(1);
});

test('detects multiple edit blocks for the same file in conflict syntax (unfenced)', () => {
    const md = `
  Now I need to modify \`GitLogTab.java\` to use the new \`GitCommitBrowserPanel\` instead of the inline code:
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
      // Commit Browser Components (to be extracted)
      private JTable commitsTable;
      private DefaultTableModel commitsTableModel;
      private JTree changesTree;
      private DefaultTreeModel changesTreeModel;
      private DefaultMutableTreeNode changesRootNode;
      private JLabel revisionTextLabel; // For "Revision:" or "Revisions:"
      private JTextArea revisionIdTextArea; // For the actual commit ID(s)
      private JTextField commitSearchTextField;
  
      // Commit Browser Context Menu Items (to be extracted)
      private JMenuItem addToContextItem;
      private JMenuItem softResetItem;
      private JMenuItem revertCommitItem;
      private JMenuItem viewChangesItem;
      private JMenuItem compareAllToLocalItem;
      private JMenuItem popStashCommitItem;
      private JMenuItem applyStashCommitItem;
      private JMenuItem dropStashCommitItem;
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
      // Commit Browser Component
      private GitCommitBrowserPanel commitBrowserPanel;
      private JTextField commitSearchTextField;
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          // ============ Commit Browser Panel (center ~80%) ============
          JPanel commitBrowserPanel = buildCommitBrowserPanel();
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          // ============ Commit Browser Panel (center ~80%) ============
          commitBrowserPanel = new GitCommitBrowserPanel(chrome, contextManager);
          JPanel commitBrowserContainer = buildCommitBrowserContainer();
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          constraints.gridx = 1; // commit browser (commits + changes)
          constraints.weightx = 0.80;
          logPanel.add(commitBrowserPanel, constraints);
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          constraints.gridx = 1; // commit browser (commits + changes)
          constraints.weightx = 0.80;
          logPanel.add(commitBrowserContainer, constraints);
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  `;

    const tree = md2hast(md);
    const editBlocks = findEditBlocks(tree);

    expect(editBlocks.length).toBe(3);
});

// ------------------------------------------------------------
// FILENAME after fence, before HEAD
// ------------------------------------------------------------
test('detects filename after fence before HEAD', () => {
  const md = `
\`\`\`
script.js
<<<<<<< SEARCH
old code
=======
new code
>>>>>>> REPLACE
\`\`\`
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('script.js');
});

// ------------------------------------------------------------
// HEAD line with no filename
// ------------------------------------------------------------
test('detects HEAD line with no filename', () => {
  const md = `
<<<<<<< SEARCH
old content
=======
new content
>>>>>>> REPLACE
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(1);
  const props = editBlocks[0].data.hProperties;
  expect(props.filename).toBe('?');
});

// ------------------------------------------------------------
// Non-edit code fence remains unchanged
// ------------------------------------------------------------
test('ignores non-edit code fence', () => {
  const md = `
\`\`\`java
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
\`\`\`
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(0);
});test('foo', () => {
  const md = `
Looking at the failed blocks and the current content, I can see the issue. The file is in a broken state where it references \`commitBrowserPanel\` (which doesn't exist) but we need to revert those changes back to use the original table model code.
  
  Let me fix the specific lines that are causing the build failure:
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          // ============ Commit Browser Panel (center ~80%) ============
          commitBrowserPanel = new GitCommitBrowserPanel(chrome, contextManager);
          JPanel commitBrowserContainer = buildCommitBrowserContainer();
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          // ============ Commit Browser Panel (center ~80%) ============
          JPanel commitBrowserPanel = buildCommitBrowserPanel();
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          constraints.gridx = 1; // commit browser (commits + changes)
          constraints.weightx = 0.80;
          logPanel.add(commitBrowserContainer, constraints);
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          constraints.gridx = 1; // commit browser (commits + changes)
          constraints.weightx = 0.80;
          logPanel.add(commitBrowserPanel, constraints);
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                          logger.warn("Could not select any local branch (target: {}, current git: {}). Clearing commits.",
                                      previouslySelectedBranch, currentGitBranch);
                          commitBrowserPanel.setCommits(List.of());
                          pullButton.setEnabled(false);
                          pushButton.setEnabled(false);
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                          logger.warn("Could not select any local branch (target: {}, current git: {}). Clearing commits.",
                                      previouslySelectedBranch, currentGitBranch);
                          commitsTableModel.setRowCount(0);
                          changesRootNode.removeAllChildren();
                          changesTreeModel.reload();
                          revisionTextLabel.setText("Revision:");
                          revisionIdTextArea.setText("N/A");
                          pullButton.setEnabled(false);
                          pushButton.setEnabled(false);
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                  SwingUtilities.invokeLater(() -> {
                      branchTableModel.setRowCount(0);
                      branchTableModel.addRow(new Object[]{"", "Error fetching branches: " + e.getMessage()});
                      remoteBranchTableModel.setRowCount(0);
                      commitBrowserPanel.setCommits(List.of());
                  });
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                  SwingUtilities.invokeLater(() -> {
                      branchTableModel.setRowCount(0);
                      branchTableModel.addRow(new Object[]{"", "Error fetching branches: " + e.getMessage()});
                      remoteBranchTableModel.setRowCount(0);
                      commitsTableModel.setRowCount(0);
                      changesRootNode.removeAllChildren();
                      changesTreeModel.reload();
                      revisionTextLabel.setText("Revision:");
                      revisionIdTextArea.setText("N/A");
                  });
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                      if (finalCommits.isEmpty()) {
                          commitBrowserPanel.setCommits(List.of());
                          return;
                      }
  
                      commitBrowserPanel.setCommits(finalCommits);
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                      if (commitRows.isEmpty()) {
                          revisionTextLabel.setText("Revision:");
                          revisionIdTextArea.setText("N/A");
                          return;
                      }
  
                      for (Object[] rowData : commitRows) {
                          commitsTableModel.addRow(rowData);
                      }
  
                      // Fit column widths for author and date
                      TableUtils.fitColumnWidth(commitsTable, 1); // Author column
                      TableUtils.fitColumnWidth(commitsTable, 2); // Date column
  
                      if (commitsTableModel.getRowCount() > 0) {
                          commitsTable.setRowSelectionInterval(0, 0);
                          // Listener will handle updates to revision display and changes tree
                      } else {
                          // Ensure changes area and revision display are cleared if no commits
                          changesRootNode.removeAllChildren();
                          changesTreeModel.reload();
                          revisionTextLabel.setText("Revision:");
                          revisionIdTextArea.setText("N/A");
                      }
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
              } catch (Exception e) {
                  logger.error("Error fetching commits for branch: " + branchName, e);
                  SwingUtilities.invokeLater(() -> {
                      commitBrowserPanel.setCommits(List.of());
                  });
              }
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
              } catch (Exception e) {
                  logger.error("Error fetching commits for branch: " + branchName, e);
                  SwingUtilities.invokeLater(() -> {
                      commitsTableModel.setRowCount(0);
                      commitsTableModel.addRow(new Object[]{
                              "Error fetching commits: " + e.getMessage(), "", "", ""
                      });
                      changesRootNode.removeAllChildren();
                      changesTreeModel.reload();
                      revisionTextLabel.setText("Revision:");
                      revisionIdTextArea.setText("N/A");
                  });
              }
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                  SwingUtilities.invokeLater(() -> {
                      if (searchResults.isEmpty()) {
                          commitBrowserPanel.setCommits(List.of());
                          chrome.systemOutput("No commits found matching: " + query);
                          return;
                      }
  
                      commitBrowserPanel.setCommits(searchResults.stream().map(c -> (ICommitInfo) c).toList());
                      chrome.systemOutput("Found " + searchResults.size() + " commits matching: " + query);
                  });
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
                  SwingUtilities.invokeLater(() -> {
                      commitsTableModel.setRowCount(0);
                      changesRootNode.removeAllChildren();
                      changesTreeModel.reload();
  
                      if (commitRows.isEmpty()) {
                          revisionTextLabel.setText("Revision:");
                          revisionIdTextArea.setText("N/A");
                          chrome.systemOutput("No commits found matching: " + query);
                          return;
                      }
  
                      for (Object[] rowData : commitRows) {
                          commitsTableModel.addRow(rowData);
                      }
  
                      chrome.systemOutput("Found " + commitRows.size() + " commits matching: " + query);
                      if (commitsTableModel.getRowCount() > 0) {
                          // Fit column widths
                          TableUtils.fitColumnWidth(commitsTable, 1); // Author
                          TableUtils.fitColumnWidth(commitsTable, 2); // Date
                          commitsTable.setRowSelectionInterval(0, 0);
                          // Listener will handle updates to revision display and changes tree
                      } else {
                          // Ensure changes area and revision display are cleared if no search results
                          changesRootNode.removeAllChildren();
                          changesTreeModel.reload();
                          revisionTextLabel.setText("Revision:");
                          revisionIdTextArea.setText("N/A");
                      }
                  });
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
              } catch (Exception e) {
                  logger.error("Error searching commits: {}", query, e);
                  SwingUtilities.invokeLater(() -> {
                      chrome.toolErrorRaw("Error searching commits: " + e.getMessage());
                      commitBrowserPanel.setCommits(List.of());
                  });
              }
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
              } catch (Exception e) {
                  logger.error("Error searching commits: {}", query, e);
                  SwingUtilities.invokeLater(() -> {
                      chrome.toolErrorRaw("Error searching commits: " + e.getMessage());
                      revisionTextLabel.setText("Revision:");
                      revisionIdTextArea.setText("N/A");
                  });
              }
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          // Commit browser panel in the center
          container.add(commitBrowserPanel, BorderLayout.CENTER);
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
          // Commit browser panel in the center
          container.add(commitBrowserPanel, BorderLayout.CENTER);
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  
  <<<<<<<< SEARCH src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
      /**
       * Selects a commit in the commits table by its ID.
       */
      public void selectCommitById(String commitId) {
          commitBrowserPanel.selectCommitById(commitId);
      }
  ======== src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
      /**
       * Selects a commit in the commits table by its ID.
       */
      public void selectCommitById(String commitId) {
          for (int i = 0; i < commitsTableModel.getRowCount(); i++) {
              // Get CommitInfo from hidden column 5
              ICommitInfo commitInfo = (ICommitInfo) commitsTableModel.getValueAt(i, 5);
              if (commitId.equals(commitInfo.id())) {
                  commitsTable.setRowSelectionInterval(i, i);
                  commitsTable.scrollRectToVisible(commitsTable.getCellRect(i, 0, true));
                  // Listener will handle updateChangesForCommits and revisionLabel
                  return;
              }
          }
  
          // If not found in the current view, let the user know
          chrome.systemOutput("Commit " + commitId.substring(0, 7) + " not found in current branch view");
      }
  >>>>>>>> REPLACE src/main/java/io/github/jbellis/brokk/gui/GitLogTab.java
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);

  expect(editBlocks.length).toBe(10);
});

// ------------------------------------------------------------
// Streaming detector for early recognition
// ------------------------------------------------------------
test('ordinary code fence containing SEARCH marker is not converted', () => {
  const md = `
\`\`\`sh
# shell script with weird text
echo "<<<<<<< SEARCH not a real conflict"
echo "line 2"
echo "line 3"
echo "line 4"
echo "line 5"
echo "line 6"
echo "line 7"
echo "line 8"
echo "line 9"
echo "line 10"
echo "line 11"
echo "line 12"
echo "line 13"
echo "line 14"
echo "line 15"
echo "line 16"
echo "line 17"
echo "line 18"
echo "line 19"
echo "line 20"
echo "line 21"
echo "line 22"
echo "line 23"
echo "line 24"
echo "line 25"
echo "line 26"
\`\`\`
  `;

  const tree = md2hast(md);
  const editBlocks = findEditBlocks(tree);
  expect(editBlocks.length).toBe(0); // no edit-block should be created
});
