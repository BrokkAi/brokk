package ai.brokk.gui.dialogs;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import ai.brokk.gui.util.BadgedIcon;
import ai.brokk.gui.util.Icons;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class DetachableTabFrame extends JFrame implements ThemeAware {
    private final Runnable redockCallback;
    private JComponent content;
    private final JPanel mainPanel;

    private final GuiTheme theme;
    private final JPanel toolbar;
    private final JPanel leftHeaderPanel;
    private final JLabel headerIconLabel;
    private final JLabel headerTitleLabel;

    private @Nullable BadgedIcon headerBadgedIcon;

    public void setContentComponent(JComponent newContent) {
        mainPanel.remove(this.content);
        this.content = newContent;
        mainPanel.add(content, BorderLayout.CENTER);
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public DetachableTabFrame(String title, JComponent content, Runnable redockCallback, GuiTheme theme) {
        super(title);
        this.content = content;
        this.redockCallback = redockCallback;
        this.theme = theme;

        Chrome.applyIcon(this);
        Chrome.maybeApplyMacFullWindowContent(this);
        ThemeTitleBarManager.maybeApplyMacTitleBar(this, title);

        this.toolbar = new JPanel(new BorderLayout(8, 0));
        this.toolbar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        this.leftHeaderPanel = new JPanel();
        this.leftHeaderPanel.setOpaque(false);
        this.leftHeaderPanel.setLayout(new BoxLayout(leftHeaderPanel, BoxLayout.X_AXIS));

        this.headerIconLabel = new JLabel();
        this.headerTitleLabel = new JLabel(title);
        this.headerTitleLabel.setOpaque(false);

        leftHeaderPanel.add(headerIconLabel);
        leftHeaderPanel.add(Box.createHorizontalStrut(6));
        leftHeaderPanel.add(headerTitleLabel);

        var rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightButtons.setOpaque(false);

        MaterialButton dockButton = new MaterialButton("");
        dockButton.setIcon(Icons.DOWNLOAD);
        dockButton.setToolTipText("Dock " + title);
        dockButton.addActionListener(e -> handleRedock());
        rightButtons.add(dockButton);

        toolbar.add(leftHeaderPanel, BorderLayout.WEST);
        toolbar.add(rightButtons, BorderLayout.EAST);

        this.mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(toolbar, BorderLayout.NORTH);
        mainPanel.add(content, BorderLayout.CENTER);

        add(mainPanel, BorderLayout.CENTER);

        // Set default close operation to DO_NOTHING so we can handle it via listener
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // Add window listener to handle close events (X button redocks)
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleRedock();
            }
        });

        // Note: We intentionally removed the frame-level redock keybinding to avoid conflicts
        // with child components' Cmd/Ctrl+W close behavior. Redock remains available
        // via the toolbar Dock button and window close.

        setSize(900, 700);
        setLocationRelativeTo(null);
    }

    public void setHeaderTitle(Icon baseIcon, String title, String tooltip, int badgeCount) {
        SwingUtilities.invokeLater(() -> {
            if (headerBadgedIcon == null) {
                headerBadgedIcon = new BadgedIcon(baseIcon, theme);
            } else if (headerBadgedIcon.getCount() == 0 && headerBadgedIcon.getIconWidth() != baseIcon.getIconWidth()) {
                headerBadgedIcon = new BadgedIcon(baseIcon, theme);
            }

            headerBadgedIcon.setCount(badgeCount, toolbar);
            headerIconLabel.setIcon(badgeCount > 0 ? headerBadgedIcon : baseIcon);

            headerTitleLabel.setText(title);
            headerTitleLabel.setToolTipText(tooltip);

            toolbar.revalidate();
            toolbar.repaint();
        });
    }

    private void handleRedock() {
        redockCallback.run();
        dispose();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (content instanceof ThemeAware themeAware) {
            themeAware.applyTheme(guiTheme);
        }
        SwingUtilities.updateComponentTreeUI(this);
    }
}
