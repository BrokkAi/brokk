package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Utility for discovering and displaying Look and Feel icons.
 * Run with "icons" argument to show a GUI browser of all available icons.
 */
public final class SwingIconUtil
{
    private static final Logger logger = LogManager.getLogger(SwingIconUtil.class);

    private SwingIconUtil() { /* no instances */ }

    /**
     * Prints all available UIManager icons to console.
     */
    public static void printAvailableIcons()
    {
        var defaults = UIManager.getDefaults();
        var iconEntries = new java.util.ArrayList<Map.Entry<String, Icon>>();
        
        for (var key : defaults.keySet()) {
            try {
                var value = UIManager.get(key);
                if (value instanceof Icon icon) {
                    iconEntries.add(Map.entry(key.toString(), icon));
                }
            } catch (Exception e) {
                // Skip if unable to load
            }
        }
        
        iconEntries.sort(Map.Entry.comparingByKey());
        
        System.out.println("=== Available Look and Feel Icons ===");
        System.out.println("Total icons: " + iconEntries.size());
        System.out.println();
        
        iconEntries.forEach(entry -> {
            var icon = entry.getValue();
            System.out.printf("%-50s [%2dx%-2d] %s%n", 
                    entry.getKey(), 
                    icon.getIconWidth(), 
                    icon.getIconHeight(),
                    icon.getClass().getSimpleName());
        });
    }

    /**
     * Shows a GUI browser of all available Look and Feel icons.
     */
    public static void showLookAndFeelIconFrame()
    {
        SwingUtilities.invokeLater(() -> {
            // Collect all icons
            var iconEntries = new java.util.ArrayList<Map.Entry<String, Icon>>();
            for (var key : UIManager.getDefaults().keySet()) {
                try {
                    var value = UIManager.get(key);
                    if (value instanceof Icon icon) {
                        iconEntries.add(Map.entry(key.toString(), icon));
                    }
                } catch (Exception e) {
                    // Skip if unable to load
                }
            }
            iconEntries.sort(Map.Entry.comparingByKey());

            // Create frame
            var frame = new JFrame("Look and Feel Icons (" + iconEntries.size() + " total)");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(1000, 700);
            frame.setLocationRelativeTo(null);

            // Create table
            var columnNames = new String[]{"Icon", "Name", "Size", "Type"};
            var tableData = new Object[iconEntries.size()][4];
            
            for (int i = 0; i < iconEntries.size(); i++) {
                var entry = iconEntries.get(i);
                var icon = entry.getValue();
                tableData[i] = new Object[]{
                    icon, entry.getKey(), 
                    icon.getIconWidth() + "x" + icon.getIconHeight(),
                    icon.getClass().getSimpleName()
                };
            }

            var table = new JTable(tableData, columnNames);
            table.setRowHeight(32);
            table.setAutoCreateRowSorter(true);
            
            // Set column widths
            var colModel = table.getColumnModel();
            colModel.getColumn(0).setMaxWidth(50);
            colModel.getColumn(1).setPreferredWidth(400);
            colModel.getColumn(2).setMaxWidth(80);
            colModel.getColumn(3).setPreferredWidth(150);

            // Icon renderer
            colModel.getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value,
                        boolean isSelected, boolean hasFocus, int row, int column) {
                    var component = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
                    if (value instanceof Icon icon) {
                        setIcon(icon);
                        setHorizontalAlignment(SwingConstants.CENTER);
                    }
                    return component;
                }
            });

            // Add search
            var searchField = new JTextField(20);
            var rowSorter = (javax.swing.table.TableRowSorter<?>) table.getRowSorter();
            searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
                
                private void filter() {
                    var text = searchField.getText().trim();
                    rowSorter.setRowFilter(text.isEmpty() ? null : 
                        javax.swing.RowFilter.regexFilter("(?i)" + text, 1));
                }
            });

            // Layout
            var searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            searchPanel.add(new JLabel("Filter:"));
            searchPanel.add(searchField);

            frame.setLayout(new BorderLayout());
            frame.add(searchPanel, BorderLayout.NORTH);
            frame.add(new JScrollPane(table), BorderLayout.CENTER);
            frame.setVisible(true);

            logger.info("Displayed icon browser with {} icons", iconEntries.size());
        });
    }

    /**
     * Main method to explore available icons.
     * 
     * @param args Use "icons" to show GUI browser, otherwise prints to console
     */
    public static void main(String[] args)
    {
        com.formdev.flatlaf.FlatLightLaf.setup();
        
        // Register Brokk's custom icons so they appear in the browser
        // Makes Null Away Grumpy
//        try {
//            new GuiTheme(null, null, null).applyTheme(false); // false = light theme
//        } catch (Exception e) {
//            logger.warn("Failed to register custom icons: {}", e.getMessage());
//        }
        
        if (args.length > 0 && "icons".equals(args[0])) {
            showLookAndFeelIconFrame();
        } else {
            printAvailableIcons();
        }
    }
}
