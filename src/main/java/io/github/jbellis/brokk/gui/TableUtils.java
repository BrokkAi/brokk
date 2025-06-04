package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

public final class TableUtils {

    /**
     * Returns the preferred row height for a table cell that renders
     * a {@link FileReferenceList}, by measuring a sample rendered cell.
     *
     * @param table The JTable for which to calculate the row height.
     * @return The preferred row height based on measurement.
     */
    /**
     * Shows a popup displaying all file references when there are more than can fit in the table cell.
     *
     * @param anchor The component that anchors the popup (usually the table or cell).
     * @param files The complete list of file references to display.
     */
    /**
     * A subclass of FileReferenceList that implements Scrollable to ensure badges wrap
     * to match the viewport width.
     */
    private static class WrappingFileReferenceList extends FileReferenceList implements Scrollable {
        public WrappingFileReferenceList(List<FileReferenceData> files) {
            super(files);
            // Make the flow layout respect width constraints
            setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2) {
                @Override
                public Dimension preferredLayoutSize(Container target) {
                    // Get the constrained width if in a viewport
                    int targetWidth = target.getWidth();
                    if (target.getParent() instanceof JViewport) {
                        targetWidth = ((JViewport) target.getParent()).getExtentSize().width;
                    }

                    // If we have zero width, use the superclass calculation
                    if (targetWidth <= 0) {
                        return super.preferredLayoutSize(target);
                    }

                    // Calculate the height given the constrained width
                    int hgap = getHgap();
                    int vgap = getVgap();
                    int maxWidth = targetWidth - (target.getInsets().left + target.getInsets().right);

                    // Calculate how many rows we need
                    int numRows = 1;
                    int x = 0;

                    for (int i = 0; i < target.getComponentCount(); i++) {
                        Component c = target.getComponent(i);
                        if (!c.isVisible()) continue;

                        Dimension d = c.getPreferredSize();
                        if (x + d.width > maxWidth && x > 0) {
                            numRows++;
                            x = 0;
                        }

                        x += d.width + hgap;
                    }

                    // Calculate total height for all rows
                    int height = 0;
                    if (numRows > 0) {
                        Component c = target.getComponent(0);
                        if (c.isVisible()) {
                            height = c.getPreferredSize().height * numRows + vgap * (numRows - 1);
                        }
                    }

                    return new Dimension(targetWidth, height + target.getInsets().top + target.getInsets().bottom);
                }
            });
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true; // Force component to match viewport width
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false; // Let vertical scrolling work normally
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 10;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }
    }

    public static void showOverflowPopup(Chrome chrome, JTable table, int row, int col, List<FileReferenceList.FileReferenceData> files) {
        // Ensure we're on EDT
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showOverflowPopup(chrome, table, row, col, files));
            return;
        }
        
        // Create a wrapping FileReferenceList with all files
        var fullList = new WrappingFileReferenceList(files);
        fullList.setOpaque(false); // For visual continuity

        // Add listeners directly to each badge component
        for (Component c : fullList.getComponents()) {
            if (c instanceof JLabel) {
                c.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                        maybeShowPopupForBadge(e);
                    }

                    @Override
                    public void mouseReleased(java.awt.event.MouseEvent e) {
                        maybeShowPopupForBadge(e);
                    }

                    private void maybeShowPopupForBadge(java.awt.event.MouseEvent e) {
                        boolean rightClick = SwingUtilities.isRightMouseButton(e);
                        boolean popupKey = e.isPopupTrigger();

                        if (rightClick || popupKey) {
                            // Find which file this badge represents
                            int index = fullList.getComponentZOrder(c);
                            if (index >= 0 && index < files.size()) {
                                // Find and close the outer popup first
                                JPopupMenu outerPopup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, c);
                                if (outerPopup != null) {
                                    outerPopup.setVisible(false);
                                }

                                // Calculate the position in screen coordinates
                                Point screenPoint = e.getLocationOnScreen();
                                Point framePoint = chrome.getFrame().getLocationOnScreen();
                                int xInFrame = screenPoint.x - framePoint.x;
                                int yInFrame = screenPoint.y - framePoint.y;

                                // Show menu anchored to the frame instead of the badge
                                ContextMenuUtils.showFileRefMenu(
                                        chrome.getFrame(),
                                        xInFrame,
                                        yInFrame,
                                        files.get(index),
                                        chrome,
                                        () -> {
                                        }  // No refresh needed for popup
                                );
                            }
                        }
                    }
                });
            }
        }

        // Calculate the proper row height using the first badge as reference
        int rowHeight = 0;
        if (!files.isEmpty()) {
            // Create a temporary badge label to measure its height
            JLabel sampleLabel = fullList.createBadgeLabel(files.get(0).getFileName());
            rowHeight = sampleLabel.getPreferredSize().height + 4; // Add a small padding
        } else {
            // Fallback to a reasonable default if no files
            rowHeight = 25;
        }

        // Find the exact column width using the table and column index
        int colWidth = table.getColumnModel().getColumn(col).getWidth();

        // Set visible rows with a maximum
        int visibleRows = Math.min(4, Math.max(1, files.size())); // At least 1 row, at most 4
        // Add explicit padding for bottom border to prevent clipping
        int borderPadding = (int) Math.ceil(FileReferenceList.BORDER_THICKNESS * 2); // Account for top and bottom border
        int preferredHeight = rowHeight * visibleRows + 6 + borderPadding;

        // Create a scrollable container with explicit scrollbar policies
        var scroll = new JScrollPane(fullList);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(new Dimension(colWidth, preferredHeight));

        // Create and configure the popup
        var popup = new JPopupMenu();
        popup.setLayout(new BorderLayout());
        popup.add(scroll);

        // Force layout to calculate proper wrapping AFTER being added to the container
        SwingUtilities.invokeLater(() -> {
            fullList.invalidate();
            fullList.revalidate();
            fullList.doLayout();
            scroll.revalidate();
        });

        // Apply theme using existing theme colors with fallbacks
        boolean isDarkTheme = UIManager.getBoolean("laf.dark");
        Color bgColor;
        Color fgColor;

        try {
            // Use panel or table colors as alternatives, since popup colors don't exist
            bgColor = ThemeColors.getColor(isDarkTheme, "table_background");
            fgColor = ThemeColors.getColor(isDarkTheme, "badge_foreground");
        } catch (IllegalArgumentException e) {
            // Fallback to standard UI colors if theme colors aren't found
            bgColor = UIManager.getColor("Panel.background");
            fgColor = UIManager.getColor("Label.foreground");
        }

        popup.setBackground(bgColor);
        fullList.setBackground(bgColor);
        scroll.setBackground(bgColor);
        popup.setForeground(fgColor);
        fullList.setForeground(fgColor);

        // Register popup with theme manager if available
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(popup);
        }
        
        // Show popup below the specific cell
        var cellRect = table.getCellRect(row, col, true);
        popup.pack(); // Ensure proper sizing
        popup.show(table, cellRect.x, cellRect.y + cellRect.height);
    }

    public static int measuredBadgeRowHeight(JTable table) {
        var sampleData = new FileReferenceList.FileReferenceData("sample.txt", "/sample.txt", null);
        var renderer = new FileReferencesTableCellRenderer();
        // The column index for getTableCellRendererComponent doesn't matter for row height
        var component = renderer.getTableCellRendererComponent(table, List.of(sampleData), false, false, 0, 0);
        return component.getPreferredSize().height;
    }

    /**
     * Adjusts the preferred width of the specified column to fit its content.
     * Also sets a maximum width to prevent columns from becoming too wide.
     *
     * @param table    The JTable whose column will be resized.
     * @param colIndex The index of the column to adjust.
     */
    public static void fitColumnWidth(JTable table, int colIndex) {
        TableColumn column = table.getColumnModel().getColumn(colIndex);
        int width = 10; // a minimum width

        // Get header width
        TableCellRenderer headerRenderer = table.getTableHeader().getDefaultRenderer();
        Component headerComp = headerRenderer.getTableCellRendererComponent(
                table, column.getHeaderValue(), false, false, 0, colIndex);
        width = Math.max(width, headerComp.getPreferredSize().width);

        // Get maximum width of cells in this column
        for (int row = 0; row < table.getRowCount(); row++) {
            TableCellRenderer cellRenderer = table.getCellRenderer(row, colIndex);
            Component comp = table.prepareRenderer(cellRenderer, row, colIndex);
            width = Math.max(width, comp.getPreferredSize().width);
        }

        column.setPreferredWidth(width);
        column.setMaxWidth(width);
    }

    /**
     * Table cell renderer for displaying file references.
     */
    static class FileReferencesTableCellRenderer implements TableCellRenderer {
        public FileReferencesTableCellRenderer() {
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column)
        {
            // Convert the value to a list of FileReferenceData
            List<FileReferenceList.FileReferenceData> fileRefs = convertToFileReferences(value);

            // Get the available width from the column
            int colWidth = table.getColumnModel().getColumn(column).getWidth();

            // Use the AdaptiveFileReferenceList instead of regular FileReferenceList
            FileReferenceList.AdaptiveFileReferenceList component =
                    new FileReferenceList.AdaptiveFileReferenceList(fileRefs, colWidth, 4);

            // Set colors based on selection
            if (isSelected) {
                component.setBackground(table.getSelectionBackground());
                component.setForeground(table.getSelectionForeground());
                component.setSelected(true);
            } else {
                component.setBackground(table.getBackground());
                component.setForeground(table.getForeground());
                component.setSelected(false);
            }

            // Ensure the component is properly painted in the table
            component.setOpaque(true);

            // Set border to match the editor's border for consistency when transitioning
            component.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

            // Update row height if necessary to fit the component, but only if there are file references
            // This preserves reasonable row height for other columns when no badges are present
            int prefHeight = component.getPreferredSize().height;
            if (!fileRefs.isEmpty() && table.getRowHeight(row) != prefHeight) {
                table.setRowHeight(row, prefHeight);
            }

            return component;
        }

        /**
         * Converts various input types to a list of FileReferenceData objects.
         */
        @SuppressWarnings("unchecked")
        public static List<FileReferenceList.FileReferenceData> convertToFileReferences(Object value) {
            if (value == null) {
                return new ArrayList<>();
            }

            if (value instanceof List) {
                return (List<FileReferenceList.FileReferenceData>) value;
            } else {
                throw new IllegalArgumentException("Input is not supported for FileReferencesTableCellRenderer. Expected List<FileReferenceData>");
            }
        }
    }

    /**
     * Component to display and interact with a list of file references.
     */
    public static class FileReferenceList extends JPanel {
        private final List<FileReferenceData> fileReferences = new ArrayList<>();
        private boolean selected = false;

        private static final int BADGE_ARC_WIDTH = 10;
        public static final float BORDER_THICKNESS = 1.5f; // Made public

        public FileReferenceList() {
            setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
            setOpaque(true);
        }

        public FileReferenceList(List<FileReferenceData> fileReferences) {
            this();
            setFileReferences(fileReferences);
        }

        /**
         * Adaptive subclass that shows only file references that fit within an available width,
         * with an optional overflow indicator.
         */
        public static class AdaptiveFileReferenceList extends FileReferenceList {
            private final int availableWidth;
            private final int hgap;

            // Fields to track visible vs. hidden files
            private List<FileReferenceData> visibleFiles = List.of();
            private List<FileReferenceData> hiddenFiles = List.of();
            private boolean hasOverflow = false;

            /**
             * Creates a new AdaptiveFileReferenceList with the given files and width constraints.
             *
             * @param files          The complete list of file references
             * @param availableWidth The maximum available width in pixels
             * @param hgap           The horizontal gap between badges
             */
            public AdaptiveFileReferenceList(List<FileReferenceData> files, int availableWidth, int hgap) {
                super();
                this.availableWidth = availableWidth;
                this.hgap = hgap;
                setFileReferences(files);
            }

            /**
             * Returns the list of files that are currently visible as individual badges.
             */
            public List<FileReferenceData> getVisibleFiles() {
                return visibleFiles;
            }

            /**
             * Returns the list of files that are hidden and represented by the overflow badge.
             */
            public List<FileReferenceData> getHiddenFiles() {
                return hiddenFiles;
            }

            /**
             * Returns whether this list has an overflow badge.
             */
            public boolean hasOverflow() {
                return hasOverflow;
            }

            @Override
            public void setFileReferences(List<FileReferenceData> fileReferences) {
                if (fileReferences == null || fileReferences.isEmpty()) {
                    this.visibleFiles = List.of();
                    this.hiddenFiles = List.of();
                    this.hasOverflow = false;
                    super.setFileReferences(fileReferences);
                    return;
                }

                // Calculate which files will fit in the available width
                var font = getFont().deriveFont(getFont().getSize() * 0.85f);
                var fm = getFontMetrics(font);
                int borderInsets = (int) Math.ceil(BORDER_THICKNESS) + 6; // Must match createBadgeLabel

                int currentX = 0;
                var visibleFilesList = new ArrayList<FileReferenceData>();

                // 1. Provisional pass - fill with real badges
                for (var ref : fileReferences) {
                    int badgeWidth = fm.stringWidth(ref.getFileName()) + borderInsets * 2;
                    if (!visibleFilesList.isEmpty()) {
                        currentX += hgap; // Add gap between badges
                    }

                    if (currentX + badgeWidth <= availableWidth) {
                        visibleFilesList.add(ref);
                        currentX += badgeWidth;
                    } else {
                        break;
                    }
                }

                int remaining = fileReferences.size() - visibleFilesList.size();

                // Store the results in our fields
                this.visibleFiles = List.copyOf(visibleFilesList);
                this.hiddenFiles = remaining > 0
                                   ? List.copyOf(fileReferences.subList(visibleFilesList.size(), fileReferences.size()))
                                   : List.of();
                this.hasOverflow = !hiddenFiles.isEmpty();

                if (hasOverflow) {
                    // 2. Compute overflow badge width
                    String overflowText = "+ " + hiddenFiles.size() + " more";
                    int overflowWidth = fm.stringWidth(overflowText) + borderInsets * 2;

                    // Add gap in front of overflow badge if there are visible files
                    int need = (visibleFiles.isEmpty() ? 0 : hgap) + overflowWidth;

                    // 3. Make space by removing trailing real badges if required
                    var mutableVisibleFiles = new ArrayList<>(visibleFiles);
                    while (!mutableVisibleFiles.isEmpty() && currentX + need > availableWidth) {
                        var last = mutableVisibleFiles.remove(mutableVisibleFiles.size() - 1);
                        int lastWidth = fm.stringWidth(last.getFileName()) + borderInsets * 2;
                        currentX -= lastWidth;
                        if (!mutableVisibleFiles.isEmpty()) currentX -= hgap;
                        remaining++;
                        overflowText = "+ " + remaining + " more";
                        overflowWidth = fm.stringWidth(overflowText) + borderInsets * 2;
                        need = (mutableVisibleFiles.isEmpty() ? 0 : hgap) + overflowWidth;
                    }

                    // Update our fields with the adjusted visible/hidden lists
                    if (mutableVisibleFiles.size() != visibleFiles.size()) {
                        this.visibleFiles = List.copyOf(mutableVisibleFiles);
                        this.hiddenFiles = List.copyOf(fileReferences.subList(mutableVisibleFiles.size(), fileReferences.size()));
                    }

                    // Display visible files first
                    super.setFileReferences(mutableVisibleFiles);

                    // Create overflow badge with updated text
                    JLabel overflow = this.createBadgeLabel(overflowText);
                    overflow.setToolTipText("Show all " + fileReferences.size() + " files");

                    // Always add overflow badge - even if no space, it will wrap to next line
                    add(overflow);
                } else {
                    // All files fit, show them all
                    super.setFileReferences(fileReferences);
                }
            }

        }


        /**
         * Updates the displayed file references
         */
        public void setFileReferences(List<FileReferenceData> fileReferences) {
            this.fileReferences.clear();
            if (fileReferences != null) {
                this.fileReferences.addAll(fileReferences);
            }

            // Rebuild the UI
            removeAll();

            // Add each file reference as a label
            for (FileReferenceData file : this.fileReferences) {
                JLabel fileLabel = createBadgeLabel(file.getFileName());
                fileLabel.setOpaque(false);

                // Set tooltip to show the full path
                fileLabel.setToolTipText(file.getFullPath());

                add(fileLabel);
            }

            revalidate();
            repaint();
        }

        /**
         * Sets the selection state of this component
         *
         * @param selected true if this component is in a selected table row
         */
        public void setSelected(boolean selected) {
            if (this.selected == selected) {
                return; // No change needed
            }

            this.selected = selected;

            // Get theme-appropriate colors
            boolean isDarkTheme = UIManager.getBoolean("laf.dark");
            Color badgeForeground = ThemeColors.getColor(isDarkTheme, "badge_foreground");
            Color selectedBadgeForeground = ThemeColors.getColor(isDarkTheme, "selected_badge_foreground");

            // Update colors of all children without rebuilding the component hierarchy
            for (Component c : getComponents()) {
                if (c instanceof JLabel) {
                    c.setForeground(selected ? selectedBadgeForeground : badgeForeground);
                }
            }

            // Repaint but don't remove/recreate components
            repaint();
        }

        /**
         * Returns whether this component is currently selected
         */
        public boolean isSelected() {
            return selected;
        }

        protected JLabel createBadgeLabel(String text) {
            JLabel label = new JLabel(text) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    // Determine if hovering
                    boolean isHovered = getMousePosition() != null;
                    // Get theme state
                    boolean isDarkTheme = UIManager.getBoolean("laf.dark");

                    // Get colors from ThemeColors
                    Color badgeBorder = ThemeColors.getColor(isDarkTheme, "badge_border");
                    Color badgeHoverBorder = ThemeColors.getColor(isDarkTheme, "badge_hover_border");
                    Color selectedBadgeBorder = ThemeColors.getColor(isDarkTheme, "selected_badge_border");

                    // Set border color based on selection state and hover state
                    Color borderColor;
                    if (selected) {
                        borderColor = isHovered ? selectedBadgeBorder.brighter() : selectedBadgeBorder;
                    } else {
                        borderColor = isHovered ? badgeHoverBorder : badgeBorder;
                    }
                    g2d.setColor(borderColor);

                    // Use a thicker stroke for the border
                    g2d.setStroke(new BasicStroke(BORDER_THICKNESS));

                    // Draw rounded rectangle border only
                    g2d.draw(new RoundRectangle2D.Float(BORDER_THICKNESS / 2, BORDER_THICKNESS / 2,
                                                        getWidth() - BORDER_THICKNESS, getHeight() - BORDER_THICKNESS,
                                                        BADGE_ARC_WIDTH, BADGE_ARC_WIDTH));

                    g2d.dispose();

                    // Then draw the text
                    super.paintComponent(g);
                }
            };

            // Style the badge - use a smaller font for table cell
            float fontSize = label.getFont().getSize() * 0.85f;
            label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize));

            boolean isDarkTheme = UIManager.getBoolean("laf.dark");
            Color badgeForeground = ThemeColors.getColor(isDarkTheme, "badge_foreground");
            Color selectedBadgeForeground = ThemeColors.getColor(isDarkTheme, "selected_badge_foreground");

            // Set foreground color based on selection state
            label.setForeground(selected ? selectedBadgeForeground : badgeForeground);

            // Combine border for stroke painting space and text padding
            int borderStrokeInset = (int) Math.ceil(BORDER_THICKNESS);
            int textPaddingVertical = 1;
            int textPaddingHorizontal = 6;
            label.setBorder(new EmptyBorder(borderStrokeInset + textPaddingVertical,
                                            borderStrokeInset + textPaddingHorizontal,
                                            borderStrokeInset + textPaddingVertical,
                                            borderStrokeInset + textPaddingHorizontal));

            return label;
        }

        /**
         * Represents a file reference with metadata for context menu usage.
         */
        public static class FileReferenceData {
            private final String fileName;
            private final String fullPath;
            private final ProjectFile projectFile; // Optional, if available

            public FileReferenceData(String fileName, String fullPath, ProjectFile projectFile) {
                this.fileName = fileName;
                this.fullPath = fullPath;
                this.projectFile = projectFile;
            }

            // Getters
            public String getFileName() {
                return fileName;
            }

            public String getFullPath() {
                return fullPath;
            }

            public ProjectFile getRepoFile() {
                return projectFile;
            }

            @Override
            public String toString() {
                return fileName;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                FileReferenceData that = (FileReferenceData) o;
                return fullPath.equals(that.fullPath);
            }

            @Override
            public int hashCode() {
                return fullPath.hashCode();
            }
        }
    }
    
    /**
     * Resolves which FileReferenceData badge is under the supplied mouse location.
     * 
     * @param pointInTableCoords The point in table coordinates
     * @param row The row index
     * @param column The column index containing the file references
     * @param table The table containing the badges
     * @param visibleReferences The list of visible file references
     * @return The FileReferenceData under the point, or null if none
     */
    public static TableUtils.FileReferenceList.FileReferenceData findClickedReference(Point pointInTableCoords,
                                                  int row,
                                                  int column,
                                                  JTable table,
                                                  List<TableUtils.FileReferenceList.FileReferenceData> visibleReferences)
    {
        // Convert to cell-local coordinates
        Rectangle cellRect = table.getCellRect(row, column, false);
        int xInCell = pointInTableCoords.x - cellRect.x;
        int yInCell = pointInTableCoords.y - cellRect.y;
        if (xInCell < 0 || yInCell < 0) return null;

        // For WorkspacePanel's new layout where badges are below description text,
        // we need to account for the description text height
        if (column == WorkspacePanel.DESCRIPTION_COLUMN) { // Description column with badges below
            // Estimate description text height (single line)
            var baseFont = table.getFont();
            var descriptionHeight = table.getFontMetrics(baseFont).getHeight() + 3; // +3 for vertical strut
            
            // Check if click is in the badge area (below description)
            if (yInCell < descriptionHeight) {
                return null; // Click was in description area, not badges
            }
            
            // Adjust Y coordinate to be relative to badge area
            yInCell -= descriptionHeight;
        }

        // Badge layout parameters – keep in sync with FileReferenceList
        final int hgap = 4;     // FlowLayout hgap in FileReferenceList

        // Get the actual renderer component to use its font metrics
        Component renderer = table.prepareRenderer(
            table.getCellRenderer(row, column), row, column);
        
        // Font used inside the badges (85 % of component font size)
        Font componentFont = renderer.getFont();
        if (componentFont == null) {
            componentFont = table.getFont(); // Fallback to table font
        }
        var badgeFont = componentFont.deriveFont(Font.PLAIN, componentFont.getSize() * 0.85f);
        var fm = renderer.getFontMetrics(badgeFont);

        int currentX = 0;
        // Calculate insets based on BORDER_THICKNESS and text padding (matching createBadgeLabel)
        int borderStrokeInset = (int) Math.ceil(FileReferenceList.BORDER_THICKNESS);
        int textPaddingHorizontal = 6; // As defined in createBadgeLabel's EmptyBorder logic
        int totalInsetsPerSide = borderStrokeInset + textPaddingHorizontal;

        for (var ref : visibleReferences) {
            int textWidth = fm.stringWidth(ref.getFileName());
            // Label width is text width + total left inset + total right inset
            int labelWidth = textWidth + (2 * totalInsetsPerSide);
            if (xInCell >= currentX && xInCell <= currentX + labelWidth) {
                return ref;
            }
            currentX += labelWidth + hgap;
        }
        // Note: This method only checks visible file badges, not the overflow badge
        // Overflow badge detection is handled by the caller checking for null result
        return null;
    }
    
    /**
     * Checks if the click is likely on the overflow badge area.
     * This is used when findClickedReference returns null but we have overflow.
     */
    public static boolean isClickOnOverflowBadge(Point pointInTableCoords,
                                                  int row,
                                                  int column,
                                                  JTable table,
                                                  List<TableUtils.FileReferenceList.FileReferenceData> visibleReferences,
                                                  boolean hasOverflow) {
        if (!hasOverflow || visibleReferences.isEmpty()) {
            return false;
        }
        
        // Convert to cell-local coordinates
        Rectangle cellRect = table.getCellRect(row, column, false);
        int xInCell = pointInTableCoords.x - cellRect.x;
        int yInCell = pointInTableCoords.y - cellRect.y;
        if (xInCell < 0 || yInCell < 0) return false;
        
        // For WorkspacePanel's new layout where badges are below description text
        if (column == WorkspacePanel.DESCRIPTION_COLUMN) {
            var baseFont = table.getFont();
            var descriptionHeight = table.getFontMetrics(baseFont).getHeight() + 3;
            if (yInCell < descriptionHeight) {
                return false;
            }
            yInCell -= descriptionHeight;
        }
        
        // Calculate where the overflow badge would be positioned
        final int hgap = 4;
        
        // Get the actual renderer component to use its font metrics
        Component renderer = table.prepareRenderer(
            table.getCellRenderer(row, column), row, column);
        
        Font componentFont = renderer.getFont();
        if (componentFont == null) {
            componentFont = table.getFont(); // Fallback to table font
        }
        var badgeFont = componentFont.deriveFont(Font.PLAIN, componentFont.getSize() * 0.85f);
        var fm = renderer.getFontMetrics(badgeFont);
        
        int borderStrokeInset = (int) Math.ceil(FileReferenceList.BORDER_THICKNESS);
        int textPaddingHorizontal = 6;
        int totalInsetsPerSide = borderStrokeInset + textPaddingHorizontal;
        
        // Calculate position after all visible badges
        int currentX = 0;
        for (var ref : visibleReferences) {
            int textWidth = fm.stringWidth(ref.getFileName());
            int labelWidth = textWidth + (2 * totalInsetsPerSide);
            currentX += labelWidth + hgap;
        }
        
        // The overflow badge starts at currentX
        // We'll be generous and consider any click past the last visible badge as overflow click
        return xInCell >= currentX - hgap; // -hgap to be more forgiving
    }
}
