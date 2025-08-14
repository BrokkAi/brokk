package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.gui.GitUiUtil;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.issues.IssueHeader;
import java.awt.*;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.swing.*;

/** ListCellRenderer that mimics IntelliJ’s issue list style. */
public class IssueHeaderCellRenderer extends JPanel implements ListCellRenderer<IssueHeader> {

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
    public Component getListCellRendererComponent(
            JList<? extends IssueHeader> list, IssueHeader value, int index, boolean isSelected, boolean cellHasFocus) {

        titleLabel.setText(value.title());

        // right-aligned author + assignees
        avatarPanel.removeAll();
        value.assignees().forEach(this::addAvatarOrName);

        var today = LocalDate.now(ZoneId.systemDefault());
        String dateText = value.updated() == null
                ? ""
                : GitUiUtil.formatRelativeDate(value.updated().toInstant(), today);

        String secondaryText = value.id() + "  " + dateText + "  by " + value.author();
        secondaryLabel.setText(secondaryText);

        // ── Compact the cell if there are no avatar icons ─────────────────────
        boolean hasAvatars = avatarPanel.getComponentCount() > 0;
        int vPad = hasAvatars ? Constants.V_GLUE : 0;

        // Adjust border and vertical gap dynamically
        setBorder(BorderFactory.createEmptyBorder(vPad, Constants.H_PAD, vPad, Constants.H_PAD));
        ((BorderLayout) getLayout()).setVgap(vPad);

        // Force the renderer to use the list’s width, preventing horizontal growth
        this.setPreferredSize(new Dimension(list.getWidth(), getPreferredSize().height));

        if (isSelected) {
            setBackground(list.getSelectionBackground());
            setForeground(list.getSelectionForeground());
        } else {
            setBackground(list.getBackground());
            setForeground(list.getForeground());
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
