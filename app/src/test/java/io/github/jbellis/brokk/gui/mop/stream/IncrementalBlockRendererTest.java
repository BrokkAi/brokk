package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.git.GitStatus;
import io.github.jbellis.brokk.gui.mop.TestUtil;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CodeBlockComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CompositeComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.EditBlockComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData;
import io.github.jbellis.brokk.gui.mop.util.ComponentUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the IncrementalBlockRenderer.
 */
public class IncrementalBlockRendererTest {

    @Test
    void topLevelCodeFenceIsRecognized() {
        var md = """
                 ```java
                 public class Test {
                     public static void main(String[] args) {
                         System.out.println("Hello");
                     }
                 }
                 ```
                 """;
        var renderer = new IncrementalBlockRenderer(false);
        var html = renderer.createHtml(md);
        var cds = renderer.buildComponentData(html);

        // Should produce one component that is a CodeBlockComponentData
        assertEquals(1, cds.size());
        assertTrue(cds.get(0) instanceof CodeBlockComponentData);
    }

    @Test
    void nestedCodeFenceIsRecognized() {
        String md = "- item\n  ```java\n  int x=1;\n  ```";
        var renderer = new IncrementalBlockRenderer(false);
        var html = renderer.createHtml(md);
        var cds = renderer.buildComponentData(html);

        // After implementing MiniParser, we should now detect the nested code fence
        assertTrue(
                cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
                "Should detect nested fence as a CompositeComponentData"
        );

        // Get the first composite and check it contains a CodeBlockComponentData
        var composite = (CompositeComponentData) cds.stream()
                .filter(c -> c instanceof CompositeComponentData)
                .findFirst()
                .orElseThrow();

        assertTrue(
                composite.children().stream().anyMatch(c -> c instanceof CodeBlockComponentData),
                "Composite should contain a CodeBlockComponentData"
        );
    }

    @Test
    void directHtmlNestedFenceIsRecognized() {
        String html = "<ul><li>Here is a code block:\n" +
                "<code-fence data-id=\"123\" data-lang=\"java\" data-content=\"System.out.println(&quot;test&quot;)\"/>\n" +
                "</li></ul>";

        var cds = TestUtil.parseHtml(html);

        // With MiniParser, we should now extract the nested code fence
        assertTrue(
                cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
                "Should detect nested fence in direct HTML as a CompositeComponentData"
        );

        // Get the composite and verify it contains a code block
        var composite = (CompositeComponentData) cds.stream()
                .filter(c -> c instanceof CompositeComponentData)
                .findFirst()
                .orElseThrow();

        assertTrue(
                composite.children().stream().anyMatch(c -> c instanceof CodeBlockComponentData),
                "Composite should contain a CodeBlockComponentData"
        );
    }

    @Test
    void testBuildComponentData() throws Exception {
        // Test with mixed content
        String html = """
                      <p>Regular markdown text</p>
                      <code-fence data-id="1" data-lang="java"><pre>public class Test {}</pre></code-fence>
                      <p>More text</p>
                      <edit-block data-id="2" data-adds="5" data-dels="3" data-file="Test.java"></edit-block>
                      <p>Final text</p>
                      """;

        var cds = TestUtil.parseHtml(html);

        // Verify results
        assertEquals(5, cds.size(), "Should have 5 components (3 markdown, 1 code, 1 edit)");

        // Check component types and order
        assertTrue(cds.get(0) instanceof MarkdownComponentData);
        assertTrue(cds.get(1) instanceof CodeBlockComponentData);
        assertTrue(cds.get(2) instanceof MarkdownComponentData);
        assertTrue(cds.get(3) instanceof EditBlockComponentData);
        assertTrue(cds.get(4) instanceof MarkdownComponentData);

        // Verify content of components
        var codeBlock = (CodeBlockComponentData) cds.get(1);
        assertEquals(1, codeBlock.id());
        assertEquals("java", codeBlock.lang());
        assertEquals("public class Test {}", codeBlock.body());

        var editBlock = (EditBlockComponentData) cds.get(3);
        assertEquals(2, editBlock.id());
        assertEquals(5, editBlock.adds());
        assertEquals(3, editBlock.dels());
        assertEquals(3, editBlock.changed()); // changed = min(5, 3) = 3
        assertEquals("Test.java", editBlock.file());
        assertEquals(GitStatus.UNKNOWN, editBlock.status());
    }

    @Test
    void compositeBuildsChildComponents() {
        var markdown = new MarkdownComponentData(1, "<p>hello</p>");
        var code = new CodeBlockComponentData(2, "int x = 1;", "java");
        var composite = new CompositeComponentData(99, List.of(markdown, code));

        var comp = composite.createComponent(false);

        // The composite should create a panel with two child components
        assertEquals(2, comp.getComponentCount());
        assertEquals(markdown.fp() + "-" + code.fp(), composite.fp());
    }

    @Test
    void compositeFingerprintChangesWhenChildChanges() {
        var md1 = "- a\n  ```java\n  x();\n  ```";
        var md2 = "- a\n  ```java\n  y();\n  ```";  // code content changed

        // Parse both markdown snippets
        var renderer1 = new IncrementalBlockRenderer(false);
        var html1 = renderer1.createHtml(md1);
        var components1 = renderer1.buildComponentData(html1);

        var renderer2 = new IncrementalBlockRenderer(false);
        var html2 = renderer2.createHtml(md2);
        var components2 = renderer2.buildComponentData(html2);

        // Both should produce a CompositeComponentData
        assertEquals(1, components1.size());
        assertEquals(1, components2.size());
        assertTrue(components1.get(0) instanceof CompositeComponentData);
        assertTrue(components2.get(0) instanceof CompositeComponentData);

        // Get fingerprints
        String fp1 = components1.get(0).fp();
        String fp2 = components2.get(0).fp();

        // Fingerprints should be different because the code content changed
        assertNotEquals(fp1, fp2, "Composite fingerprint should change when child content changes");
    }

    @Test
    void incrementalRendererDetectsNestedContentChange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var md1 = "- a\n  ```java\n  x();\n  ```";
            var md2 = "- a\n  ```java\n  y();\n  ```";  // child changed

            // Create a renderer and update with first markdown
            var renderer = new IncrementalBlockRenderer(false);
            renderer.update(md1);

            // Get the first component to check later
            var rootPanel = renderer.getRoot();
            assertTrue(rootPanel.getComponentCount() > 0, "Root panel should have at least one component after first update.");
            var firstComponent = rootPanel.getComponent(0);

            // Update with second markdown
            renderer.update(md2);

            // The component should have been updated, not recreated
            // So it should be the same component instance
            assertEquals(firstComponent, rootPanel.getComponent(0),
                         "Component instance should be reused even when nested content changes");

            // But the content should have been updated
            var panel = (JPanel) rootPanel.getComponent(0);
            assertTrue(panel.getComponentCount() > 0, "Panel should contain components");
        });
    }

    @Test
    void multipleMixedCustomTagsAreParsed() {
        String md = """
                    Here's a list with mixed content:
                    
                    - Item 1: normal text
                    - Item 2: with code
                      ```java
                      System.out.println("Item 2");
                      ```
                    - Item 3: with edit block
                      <<<<<<< SEARCH Test.java
                      void test() {}
                      ======= Test.java
                      void test() { return; }
                      >>>>>>> REPLACE Test.java
                    """;

        var renderer = new IncrementalBlockRenderer(false);
        var html = renderer.createHtml(md);
        var cds = renderer.buildComponentData(html);

        // Should produce at least one composite
        assertTrue(
                cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
                "Should detect mixed custom tags as a CompositeComponentData"
        );

        // Find any composite and verify it has both types of custom blocks
        var composite = cds.stream()
                .filter(c -> c instanceof CompositeComponentData)
                .map(c -> (CompositeComponentData) c)
                .findFirst()
                .orElseThrow();

        boolean hasCodeBlock = composite.children().stream()
                .anyMatch(c -> c instanceof CodeBlockComponentData);

        boolean hasEditBlock = composite.children().stream()
                .anyMatch(c -> c instanceof EditBlockComponentData);

        assertTrue(hasCodeBlock || hasEditBlock,
                   "Composite should contain either a CodeBlockComponentData or EditBlockComponentData");
    }

    @Test
    void multipleMixedCustomTagsAreParsed2() {
        String md = """
                    Here's a list with mixed content:
                    
                    - Item 1: normal text
                    - Item 2: with code
                      ```java
                      System.out.println("Item 2");
                      ```
                    - Item 3: with edit block
                      ```
                      Test.java
                      <<<<<<< SEARCH
                      void test() {}
                      ======= Test.java
                      void test() { return; }
                      >>>>>>> REPLACE
                      ```
                    """;

        var renderer = new IncrementalBlockRenderer(false);
        var html = renderer.createHtml(md);
        var cds = renderer.buildComponentData(html);

        // Should produce at least one composite
        assertTrue(
                cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
                "Should detect mixed custom tags as a CompositeComponentData"
        );

        // Find any composite and verify it has both types of custom blocks
        var composite = cds.stream()
                .filter(c -> c instanceof CompositeComponentData)
                .map(c -> (CompositeComponentData) c)
                .findFirst()
                .orElseThrow();

        boolean hasCodeBlock = composite.children().stream()
                .anyMatch(c -> c instanceof CodeBlockComponentData);

        boolean hasEditBlock = composite.children().stream()
                .anyMatch(c -> c instanceof EditBlockComponentData);

        assertTrue(hasCodeBlock || hasEditBlock,
                   "Composite should contain either a CodeBlockComponentData or EditBlockComponentData");
    }

    @Test
    void editBlockIsIgnoredWhenDisabled() {
        String md = """
                    Regular text
                    
                    <edit-block data-id="99" data-adds="1" data-dels="1" data-file="Foo.java"/>
                    """;

        var renderer = new IncrementalBlockRenderer(false /*dark*/, false /*enableEditBlocks*/);

        // Parse the markdown directly at the model level
        var html = renderer.createHtml(md);
        var components = renderer.buildComponentData(html);

        // Check that no EditBlockComponentData objects were created
        assertTrue(
                components.stream().noneMatch(c -> c instanceof EditBlockComponentData),
                "No EditBlockComponentData must be produced when feature is disabled"
        );
    }

    @Test
    void streamingSessionKeepsComponentInstances() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var renderer = new IncrementalBlockRenderer(false);
            var root = renderer.getRoot();

            // 1st chunk - initial content (heading + paragraph)
            renderer.update("## Title\nThis is an example ");

            // Store all components for reuse verification
            assertTrue(root.getComponentCount() > 0, "Root panel should have components after first chunk.");
            var components = new Component[root.getComponentCount()];
            for (int i = 0; i < root.getComponentCount(); i++) {
                components[i] = root.getComponent(i);
                // Component might be a JEditorPane directly or wrapped in a JPanel
                var editorPane = (JEditorPane) components[i];
                System.out.println("Component " + i + " content: " + editorPane.getText());
            }

            // 2nd chunk - completes paragraph and adds a code block
            renderer.update("## Title\nThis is an example of streaming.\n```java\nSystem.out");

            // Components should be reused
            assertSame(components[0], root.getComponent(0), "## Title should be reused");
            assertSame(components[1], root.getComponent(1), "This is an example... should be reused");

            // Verify content through the component hierarchy
            var lastComponent = (JPanel) root.getComponent(root.getComponentCount() - 1);
            var codeEditor = ComponentUtils.findComponentsOfType(lastComponent, RSyntaxTextArea.class).getFirst();
            assertTrue(codeEditor.getText().contains("System.out"), "Content should contain code block");

            // 3rd chunk - adds a second paragraph
            renderer.update("""
                            ## Title
                            This is an example of streaming.
                            ```java
                            System.out.println("hi");
                            ```
                            Let's add a second paragraph.
                            """);

            // Message bubbles should still be reused
            assertSame(components[0], root.getComponent(0), "## Title should be reused");
            assertSame(components[1], root.getComponent(1), "This is an example... should be reused");
            assertTrue(codeEditor.getText().contains("System.out.println"), "Content should contain code block");

            // Verify final content
            assertTrue(((JEditorPane) root.getComponent(3)).getText().contains("Let's add a second paragraph."), "Content should contain second paragraph");
        });
    }

    @Test
    void consecutiveMarkdownBlocksAreMerged() throws Exception {
        // Two prose paragraphs -> should become one block after compaction
        String md = """
                    First paragraph
                    
                    Second paragraph
                    """;

        var renderer = new IncrementalBlockRenderer(false);
        renderer.update(md); // streaming finished, lastMarkdown is set
        var componentsBefore = renderer.buildComponentData(renderer.createHtml(md)); // uses lastMarkdown
        var preMergeCount = componentsBefore.size();

        var compactedSnapshot = renderer.buildCompactedSnapshot(1L);
        SwingUtilities.invokeAndWait(() -> renderer.applyCompactedSnapshot(compactedSnapshot, 1L));
        
        var postMergeCount = renderer.getRoot().getComponentCount();

        assertTrue(preMergeCount > postMergeCount,
                   "Markdown blocks must be merged after compaction. Pre-compaction count: " + preMergeCount + ", Post-compaction count: " + postMergeCount);
        assertEquals(1, postMergeCount, "All markdown blocks should be merged into one");
    }

    @Test
    void specialBlocksBreakMarkdownSequence() throws Exception {
        // prose - code - prose  =>  must stay 3 blocks (2 markdown, 1 code)
        String md = """
                    Intro text
                    
                    ```java
                    int x = 1;
                    ```
                    
                    Outro text
                    """;

        var renderer = new IncrementalBlockRenderer(false);
        renderer.update(md);

        // Get the compacted form
        var compactedSnapshot = renderer.buildCompactedSnapshot(1L);
        // Apply to UI
        SwingUtilities.invokeAndWait(() -> renderer.applyCompactedSnapshot(compactedSnapshot, 1L));

        assertNotNull(compactedSnapshot);
        long mdCount = compactedSnapshot.stream()
                .filter(c -> c instanceof MarkdownComponentData)
                .count();
        long codeCount = compactedSnapshot.stream()
                .filter(c -> c instanceof CodeBlockComponentData)
                .count();

        assertEquals(2, mdCount, "Markdown before and after code must not merge in snapshot");
        assertEquals(1, codeCount, "Code block must remain intact in snapshot");
        
        // Check UI component count
        assertEquals(3, renderer.getRoot().getComponentCount(), "UI should have 3 components after compaction");
    }

    @Test
    void idOfFirstMarkdownBlockIsPreserved() throws Exception {
        String md = "A\n\nB"; // paragraphs A and B
        var renderer = new IncrementalBlockRenderer(false);
        renderer.update(md);

        // capture the first markdown id before merging from original parsing
        var componentsBefore = renderer.buildComponentData(renderer.createHtml(md));
        var firstIdBefore = ((MarkdownComponentData) componentsBefore.stream()
                                .filter(c -> c instanceof MarkdownComponentData)
                                .findFirst().orElseThrow()).id();

        // Get the compacted form
        var compactedSnapshot = renderer.buildCompactedSnapshot(1L);
        SwingUtilities.invokeAndWait(() -> renderer.applyCompactedSnapshot(compactedSnapshot, 1L));

        // Assert on the compacted snapshot
        assertNotNull(compactedSnapshot);
        assertFalse(compactedSnapshot.isEmpty());
        var firstMarkdownAfterCompaction = (MarkdownComponentData) compactedSnapshot.stream()
                                               .filter(c -> c instanceof MarkdownComponentData)
                                               .findFirst().orElseThrow();

        assertEquals(firstIdBefore, firstMarkdownAfterCompaction.id(), "Merged markdown must keep the id of the first block");
        assertEquals(1, renderer.getRoot().getComponentCount(), "UI should have one component after compaction");
    }

    @Test
    void compactMarkdownIsIdempotent() throws Exception {
        String md = """
                    First paragraph
                    
                    Second paragraph
                    """;

        var renderer = new IncrementalBlockRenderer(false);
        renderer.update(md);
        var html = renderer.createHtml(md); 
        var componentsBefore = renderer.buildComponentData(html); 
        var preMergeCount = componentsBefore.size();

        // First compaction
        var compactedSnapshot1 = renderer.buildCompactedSnapshot(1L);
        SwingUtilities.invokeAndWait(() -> renderer.applyCompactedSnapshot(compactedSnapshot1, 1L));
        var postMergeCountFirst = renderer.getRoot().getComponentCount();

        // Second compaction attempt
        var compactedSnapshot2 = renderer.buildCompactedSnapshot(2L); 
        SwingUtilities.invokeAndWait(() -> renderer.applyCompactedSnapshot(compactedSnapshot2, 2L));
        var postMergeCountSecond = renderer.getRoot().getComponentCount();

        assertTrue(preMergeCount > postMergeCountFirst,
                   "Markdown blocks must be merged after first compaction. Pre: " + preMergeCount + ", Post1: " + postMergeCountFirst);
        assertEquals(postMergeCountFirst, postMergeCountSecond, "Second compaction should not change component count");
    }

    @Test
    void heightDoesNotGrowAndScrollIsPreserved() throws Exception {
        // All Swing work on the EDT
        SwingUtilities.invokeAndWait(() -> {
            var renderer = new IncrementalBlockRenderer(false);
            var markdown = """
                           Is it worth writing a unit test measuring the effect of our changes? Is it even easy possible?
                           
                           ## Should we write a unit-test that _measures_ the visual effect of the CSS / compaction changes?
                           
                           ### 1. What exactly would we test?
                           
                           | Concern we have | Observable in code? | How we could measure |
                           |-----------------|---------------------|----------------------|
                           | “The message gets shorter after `compactMarkdown()`” | Yes – compare `root.getPreferredSize().height` (or the summed `getHeight()` of child components) before vs. after. | Straightforward JUnit test, headless. |
                           | “The viewport scroll jump is acceptable” | Partly – we can capture `viewport.getViewPosition()` before/after. | Possible but requires constructing a `JScrollPane` hierarchy in the test; still headless-safe. |
                           | “Visual spacing between paragraphs looks nice” | No – subjective. Needs screenshot comparison or human eye. | Automated UI screenshot tests → heavy infra, brittle. |
                           | “Different L&F / font resolutions keep the delta small” | Not easily – depends on platform rendering. | Needs per-OS CI runners or Docker image with Xvfb. |
                           
                           So _some_ effects are objectively measurable (component heights, scroll offset),
                           others are aesthetic and cannot be captured by a pure unit test.
                           
                           ### 2. How hard is it to test the measurable bits?
                           
                           *Creating the scene*
                           
                           ```java
                           var renderer = new IncrementalBlockRenderer(false);
                           renderer.update(sampleMarkdown);
                           // int h1 = renderer.getRoot().getPreferredSize().height;
                           
                           renderer.compactMarkdown();
                           // int h2 = renderer.getRoot().getPreferredSize().height;
                           assertTrue(h1 - h2 >= expectedDelta);
                           ```
                           
                           That runs in a **headless** JVM (JUnit already sets `java.awt.headless=true` in
                           most build tools today). Swing still instantiates fine – it just uses logical
                           fonts.  So a height-comparison test is _easy_.
                           
                           *Scroll restoration* is also feasible:
                           
                           ```java
                           var pane = new JScrollPane(renderer.getRoot());
                           pane.getVerticalScrollBar().setValue(200);
                           Point before = pane.getViewport().getViewPosition();
                           
                           renderer.compactMarkdown();
                           Point after = pane.getViewport().getViewPosition();
                           
                           assertEquals(before, after);
                           ```
                           
                           Again, headless-safe.
                           
                           ### 3. Is it **worth** having such a test?
                           
                           Pros
                           * Protects us against accidentally re-introducing duplicated padding later.
                           * Fast (<10 ms) – no screenshot machinery.
                           * Clearly documents the expected invariant (height does **not increase**, scroll
                             position is **preserved**).
                           
                           Cons
                           * The exact pixel delta varies with font metrics across JDK versions.  We would
                             need to assert “h2 ≤ h1” (no growth) rather than an absolute number, or use a
                             generous tolerance.
                           * Scroll tests can become flaky if someone changes the test data (longer text
                             may push the anchor component out of view).
                           
                           ### 4. Recommended approach
                           
                           1. **Write a lightweight unit test** that asserts:
                              * The overall preferred height **does not increase** after compaction.
                              * The first visible child component instance is **still present** (IDs
                                stable → reconciler reuse).
                           2. Avoid absolute pixel expectations; use relational assertions
                              (`>=`, `<=`, _same instance_).
                           3. Skip purely visual judgments (paragraph aesthetics); leave those to manual
                              QA or a higher-level UI test suite if we ever adopt screenshot testing.
                           
                           ### TL;DR
                           
                           Yes, a small headless unit test that checks *height does not grow* and *scroll
                           position is preserved* gives good regression coverage for almost no cost.
                           Measuring subjective “looks nicer” is not practical in a unit test.
                           
                           """;

            /* ---------- initial render ---------- */
            renderer.update(markdown);
            var root = renderer.getRoot();
            int heightBefore = root.getPreferredSize().height;

            // Put the root into a scroll pane and scroll down a bit
            var scrollPane = new JScrollPane(root);
            var viewport = scrollPane.getViewport();
            viewport.setViewPosition(new Point(0, heightBefore / 2));
            Point posBefore = viewport.getViewPosition();

            // Remember the first Swing component instance to ensure reuse
            var firstCompBefore = root.getComponent(0);

            /* ---------- compaction ---------- */
            var compactedSnapshot = renderer.buildCompactedSnapshot(1L);
            renderer.applyCompactedSnapshot(compactedSnapshot, 1L); // Already on EDT

            // Layout the container again so preferred sizes are up-to-date
            scrollPane.revalidate();
            scrollPane.doLayout();

            int heightAfter = root.getPreferredSize().height;
            Point posAfter = viewport.getViewPosition();
            var firstCompAfter = root.getComponent(0);

            System.out.println("Height before: " + heightBefore);
            System.out.println("Height after: " + heightAfter);
            System.out.println("Change: " + (heightBefore - heightAfter));
            
            /* ---------- assertions ---------- */
            assertTrue(heightAfter <= heightBefore,
                       "Panel must not grow after compaction");
            assertEquals(posBefore, posAfter,
                         "Viewport scroll position must remain unchanged");
            assertSame(firstCompBefore, firstCompAfter,
                       "First child component instance should be reused");
        });
    }
}
