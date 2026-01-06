package ai.brokk.gui.dialogs;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class DetachableTabFrame extends JFrame implements ThemeAware {
    private final Runnable redockCallback;
    private JComponent content;
    private final JPanel mainPanel;

    public void setContentComponent(JComponent newContent) {
        mainPanel.remove(this.content);
        this.content = newContent;
        mainPanel.add(content, BorderLayout.CENTER);
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public DetachableTabFrame(String title, JComponent content, Icon icon, Runnable redockCallback) {
        super(title);
        this.content = content;
        this.redockCallback = redockCallback;

        // Apply icon, macOS full-window-content, and title bar styling
        Chrome.applyIcon(this);
        Chrome.maybeApplyMacFullWindowContent(this);
        ThemeTitleBarManager.maybeApplyMacTitleBar(this, title);

        // Create toolbar with dock button
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        MaterialButton dockButton = new MaterialButton("");
        dockButton.setIcon(Icons.VISIBILITY);
        dockButton.setToolTipText("Dock " + title);
        dockButton.addActionListener(e -> handleRedock());
        toolbar.add(dockButton);

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

        // Register Cmd/Ctrl+W shortcut to redock
        KeyStroke closeKeyStroke = GlobalUiSettings.getKeybinding(
                "global.closeTab", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W));
        var root = this.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(closeKeyStroke, "redockTab");
        root.getActionMap().put("redockTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleRedock();
            }
        });

        setSize(900, 700);
        setLocationRelativeTo(null);
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
