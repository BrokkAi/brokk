package ai.brokk.gui.components;

import ai.brokk.gui.Constants;
import java.awt.*;
import java.util.Objects;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;

/**
 * TableCellRenderer that mimics the Issue list style for Pull-Requests. It expects the JTable row to contain: [#number,
 * title, author, updated-string, status] with the title column being the one rendered.
 */
public class PullRequestHeaderCellRenderer extends JPanel implements TableCellRenderer {
    private final JLabel titleLabel = new JLabel();
    private final JPanel avatarPanel = new JPanel();
    private final JLabel secondaryLabel = new JLabel();

    public PullRequestHeaderCellRenderer() {
        super(new BorderLayout(Constants.H_GAP, Constants.V_GLUE));
        setBorder(
                BorderFactory.createEmptyBorder(Constants.V_GLUE, Constants.H_PAD, Constants.V_GLUE, Constants.H_PAD));

        // Title and avatars
        JPanel north = new JPanel(new BorderLayout(Constants.H_GAP, 0));
        north.setOpaque(false);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        north.add(titleLabel, BorderLayout.CENTER);

        avatarPanel.setOpaque(false);
        avatarPanel.setLayout(new FlowLayout(FlowLayout.RIGHT, Constants.H_GAP / 2, 0));
        north.add(avatarPanel, BorderLayout.EAST);

        add(north, BorderLayout.NORTH);

        // Secondary info
        secondaryLabel.setFont(secondaryLabel
                .getFont()
                .deriveFont(Font.PLAIN, secondaryLabel.getFont().getSize() - 1));
        secondaryLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        add(secondaryLabel, BorderLayout.CENTER);

        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

        // Collect other row data from hidden columns
        String number = table.getModel().getValueAt(row, 0).toString();
        String author = table.getModel().getValueAt(row, 2).toString();
        String updated = table.getModel().getValueAt(row, 3).toString();

        titleLabel.setText(Objects.toString(value, ""));

        avatarPanel.removeAll();
        addAvatarOrName(author); // show the author on the right

        String secondaryText = number + "  " + updated + "  by " + author;
        secondaryLabel.setText(secondaryText);

        // Compact cell if no avatars
        boolean hasAvatars = avatarPanel.getComponentCount() > 0;
        int vPad = hasAvatars ? Constants.V_GLUE : 0;
        setBorder(BorderFactory.createEmptyBorder(vPad, Constants.H_PAD, vPad, Constants.H_PAD));
        ((BorderLayout) getLayout()).setVgap(vPad);

        if (isSelected) {
            setBackground(table.getSelectionBackground());
            setForeground(table.getSelectionForeground());
        } else {
            setBackground(table.getBackground());
            setForeground(table.getForeground());
        }

        // Force width to table width
        setPreferredSize(new Dimension(table.getWidth(), getPreferredSize().height));
        return this;
    }

    @SuppressWarnings({"unused", "UnusedVariable"})
    private void addAvatarOrName(String name) {
        // Intentionally do not show the author avatar/name on the first (title) row.
        // The author text is still displayed in the secondary (second) line of the cell
        // and the author column/data remains available in the table model for filtering.
        // The 'name' parameter is intentionally unused to preserve the method signature
        // for callers that pass the author (used elsewhere for filtering).
    }
}
