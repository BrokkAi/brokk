package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.git.GitStatus;
import io.github.jbellis.brokk.gui.mop.RoundedLineBorder;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Represents an edit block for file diffs.
 */
public record EditBlockComponentData(int id, int adds, int dels, int changed, String file, GitStatus status) implements ComponentData {
    @Override
    public String fp() {
        return adds + "|" + dels + "|" + changed + "|" + status;
    }
    
    /**
     * Returns the appropriate symbol for the git status.
     */
    private String symbolFor(GitStatus status) {
        return switch (status) {
            case UNKNOWN -> "";
            case ADDED -> "A";
            case MODIFIED -> "M";
            case DELETED -> "D";
        };
    }
    
    /**
     * Returns the appropriate color key for the git status text/badge.
     */
    private String colorKeyFor(GitStatus status) {
        return switch (status) {
            case UNKNOWN -> "git_status_unknown";
            case ADDED -> "git_status_added";
            case MODIFIED -> "git_status_modified";
            case DELETED -> "git_status_deleted";
        };
    }
    
    /**
     * Creates a simple label for the git status badge (just the letter).
     */
    private JLabel createBadgeLabel(boolean darkTheme) {
        var badgeLabel = new JLabel(symbolFor(status));
        badgeLabel.setForeground(ThemeColors.getColor(darkTheme, colorKeyFor(status)));
        badgeLabel.setFont(badgeLabel.getFont().deriveFont(Font.BOLD));
        // Add padding to the left and right of the badge
        badgeLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 10)); // Pad right more for spacing
        badgeLabel.setToolTipText(status.name());
        return badgeLabel;
    }
    
    /**
     * Creates a label for the filename.
     */
    private JLabel createFilenameLabel(boolean darkTheme) {
        var filenameLabel = new JLabel(file());
        filenameLabel.setForeground(ThemeColors.getColor(darkTheme, "chat_text"));
        return filenameLabel;
    }
    
    /**
     * Creates a panel containing the add/delete statistics labels.
     */
    private JPanel createStatsPanel(boolean darkTheme) {
        var statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0)); // Right-aligned, 5px gap
        statsPanel.setOpaque(false);
        
        var addsLabel = new JLabel("+" + adds());
        addsLabel.setName("addsLabel"); // For lookup in updateComponent
        addsLabel.setForeground(ThemeColors.getColor(darkTheme, "git_status_added"));
        
        var delsLabel = new JLabel("-" + dels());
        delsLabel.setName("delsLabel"); // For lookup in updateComponent
        delsLabel.setForeground(ThemeColors.getColor(darkTheme, "git_status_deleted"));
        
        var changedLabel = new JLabel("~" + changed());
        changedLabel.setName("changedLabel"); // For lookup in updateComponent
        changedLabel.setForeground(ThemeColors.getColor(darkTheme, "git_changed"));
        
        // Always add labels, but set initial visibility based on count
        statsPanel.add(addsLabel);
        addsLabel.setVisible(adds() > 0);
        
        statsPanel.add(delsLabel);
        delsLabel.setVisible(dels() > 0);

        statsPanel.add(changedLabel);
        changedLabel.setVisible(changed() > 0);
        
        // Add padding to the right of the stats
        statsPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
        
        return statsPanel;
    }
    
    @Override
    public JComponent createComponent(boolean darkTheme) {
        JPanel panel = new JPanel();
        // Use BoxLayout along the X-axis
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(true);
        
        // Background color setup
        Color bgColor = darkTheme ? ThemeColors.getColor(true, "code_block_background") :
                                  ThemeColors.getColor(false, "message_background");
        panel.setBackground(bgColor);
        
        // Create components
        JLabel badgeLabel = createBadgeLabel(darkTheme);
        badgeLabel.setName("badgeLabel"); // For lookup
        
        JLabel filenameLabel = createFilenameLabel(darkTheme);
        filenameLabel.setName("filenameLabel"); // For lookup
        filenameLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5)); // Add right padding to filename
        
        JPanel statsPanel = createStatsPanel(darkTheme);
        statsPanel.setName("statsPanel"); // For potential lookup/update
        
        // Add components to the panel
        panel.add(badgeLabel);
        panel.add(filenameLabel);
        panel.add(Box.createHorizontalGlue()); // Pushes stats to the right edge
        panel.add(statsPanel);
        
        // Overall styling with RoundedLineBorder
        int radius = 8;
        int thickness = 1;
        Color borderColor = darkTheme ? bgColor.brighter() : bgColor.darker();
        
        // The border now also handles the fill
        Border roundedBorder = new RoundedLineBorder(borderColor, thickness, radius, true, bgColor);
        // Add padding inside the border
        Border paddingBorder = BorderFactory.createEmptyBorder(5, 5, 5, 5);
        panel.setBorder(BorderFactory.createCompoundBorder(roundedBorder, paddingBorder));
        
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Constrain height to preferred height to prevent vertical stretching in BoxLayout
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height)); 
        
        return panel;
    }
    
    @Override
    public void updateComponent(JComponent component) {
        if (component instanceof JPanel panel) {
            boolean darkTheme = panel.getBackground().equals(ThemeColors.getColor(true, "code_block_background"));
            
            // Find components by name
            JLabel badgeLabel = findComponentByName(panel, JLabel.class, "badgeLabel");
            JLabel filenameLabel = findComponentByName(panel, JLabel.class, "filenameLabel");
              JLabel addsLabel = findComponentByName(panel, JLabel.class, "addsLabel");
              JLabel delsLabel = findComponentByName(panel, JLabel.class, "delsLabel");
              JLabel changedLabel = findComponentByName(panel, JLabel.class, "changedLabel");
              
              // Update badge
              if (badgeLabel != null) {
                badgeLabel.setText(symbolFor(status));
                badgeLabel.setForeground(ThemeColors.getColor(darkTheme, colorKeyFor(status)));
                badgeLabel.setToolTipText(status.name());
            }
            
            // Update filename
            if (filenameLabel != null) {
                filenameLabel.setText(file());
                filenameLabel.setForeground(ThemeColors.getColor(darkTheme, "chat_text")); // Re-apply in case theme changed
            }
            
            // Update stats
            if (addsLabel != null) {
                addsLabel.setText("+" + adds());
                addsLabel.setForeground(ThemeColors.getColor(darkTheme, "git_status_added"));
            }
            if (delsLabel != null) {
                  delsLabel.setText("-" + dels());
                  delsLabel.setForeground(ThemeColors.getColor(darkTheme, "git_status_deleted"));
                  delsLabel.setVisible(dels() > 0); // Hide if zero
              }
              if (changedLabel != null) {
                  changedLabel.setText("~" + changed());
                  changedLabel.setForeground(ThemeColors.getColor(darkTheme, "git_changed"));
                  changedLabel.setVisible(changed() > 0); // Hide if zero
              }
              // Ensure addsLabel is also handled for visibility
              if (addsLabel != null) {
                  addsLabel.setVisible(adds() > 0);
              }
              
              // Update background and border color if theme changed (simple check)
            Color expectedBgColor = darkTheme ? ThemeColors.getColor(true, "code_block_background") :
                                               ThemeColors.getColor(false, "message_background");
            if (!panel.getBackground().equals(expectedBgColor)) {
                panel.setBackground(expectedBgColor);
                // Update the border which now includes the background fill
                Color borderColor = darkTheme ? expectedBgColor.brighter() : expectedBgColor.darker();
                int radius = 8;
                int thickness = 1;
                Border roundedBorder = new RoundedLineBorder(borderColor, thickness, radius, true, expectedBgColor);
                Border paddingBorder = BorderFactory.createEmptyBorder(5, 5, 5, 5); // Keep padding consistent
                panel.setBorder(BorderFactory.createCompoundBorder(roundedBorder, paddingBorder));
            }
            
            // Ensure layout is recalculated and component repainted after updates
            panel.revalidate();
            panel.repaint();
        }
    }
    
    
    /**
     * Finds the first component of a specific type and name within a container.
     */
    private <T extends Component> @Nullable T findComponentByName(Container container, Class<T> type, String name) {
        for (Component comp : container.getComponents()) {
            if (type.isInstance(comp) && name.equals(comp.getName())) {
                return type.cast(comp);
            }
            if (comp instanceof Container nestedContainer) {
                T found = findComponentByName(nestedContainer, type, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
