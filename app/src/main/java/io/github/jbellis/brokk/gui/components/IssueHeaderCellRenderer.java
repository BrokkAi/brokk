package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.gui.SwingUtil;
import java.awt.*;
import java.util.Objects;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;

/**
 * TableCellRenderer that mimics IntelliJ-style issue rows. It expects the JTable row to contain: [id, title, author,
 * updated] with the title column being the one rendered.
 */
public class IssueHeaderCellRenderer extends JPanel implements TableCellRenderer {

    private final JLabel titleLabel = new JLabel();
    private final JPanel avatarPanel = new JPanel();
    private final JLabel secondaryLabel = new JLabel();

    public IssueHeaderCellRenderer() {
        super(new BorderLayout(Constants.H_GAP, Constants.V_GLUE));
        setBorder(
                BorderFactory.createEmptyBorder(Constants.V_GLUE, Constants.H_PAD, Constants.V_GLUE, Constants.H_PAD));

        // Title and avatars
        var north = new JPanel(new BorderLayout(Constants.H_GAP, 0));
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
        add(secondaryLabel, BorderLayout.SOUTH);

        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

        String id = table.getModel().getValueAt(row, 0).toString();
        String author = table.getModel().getValueAt(row, 2).toString();
        String updated = table.getModel().getValueAt(row, 3).toString();

        titleLabel.setText(Objects.toString(value, ""));

        avatarPanel.removeAll();
        addAvatarOrName(author); // show author on the right

        String secondaryText = id + "  " + updated + "  by " + author;
        secondaryLabel.setText(secondaryText);

        // Compact if no avatars
        boolean hasAvatars = avatarPanel.getComponentCount() > 0;
        int vPad = hasAvatars ? Constants.V_GLUE : 0;
        setBorder(BorderFactory.createEmptyBorder(vPad, Constants.H_PAD, vPad, Constants.H_PAD));
        ((BorderLayout) getLayout()).setVgap(vPad);

        setPreferredSize(new Dimension(table.getWidth(), getPreferredSize().height));

        if (isSelected) {
            setBackground(table.getSelectionBackground());
            setForeground(table.getSelectionForeground());
        } else {
            setBackground(table.getBackground());
            setForeground(table.getForeground());
        }
        return this;
    }

    private void addAvatarOrName(String name) {
        if (name.isBlank()) {
            return;
        }
        Icon userIcon = SwingUtil.uiIcon("Brokk.person");
        JLabel lbl = new JLabel(name, userIcon, SwingConstants.LEFT);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, lbl.getFont().getSize() - 1));
        avatarPanel.add(lbl);
    }
}
