package ai.brokk.gui.components;

import ai.brokk.util.Environment;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A reusable label component that displays a clickable link to install the Brokk GitHub App.
 * When clicked, it opens the GitHub App installation page in the user's browser.
 */
public class GitHubAppInstallLabel extends JLabel {
    private static final Logger logger = LogManager.getLogger(GitHubAppInstallLabel.class);
    private static final String GITHUB_APP_INSTALL_URL = "https://github.com/apps/brokkai/installations/select_target";
    private static final String DEFAULT_LABEL_TEXT =
            "<html>To use Brokk with your repositories, <a href=\"\">install the GitHub App</a>.</html>";

    private final Component parentComponent;

    /**
     * Creates a new GitHub App installation label with custom text and color.
     *
     * @param parentComponent The parent component, used to determine window ancestry for browser operations
     * @param labelText The text to display (can include HTML).
     * @param foregroundColor The color for the label text.
     */
    public GitHubAppInstallLabel(Component parentComponent, String labelText, Color foregroundColor) {
        super(labelText);
        this.parentComponent = parentComponent;
        setForeground(foregroundColor);
        setupComponent();
    }

    /**
     * Creates a new GitHub App installation label with custom text.
     *
     * @param parentComponent The parent component, used to determine window ancestry for browser operations
     * @param labelText The text to display (can include HTML).
     */
    public GitHubAppInstallLabel(Component parentComponent, String labelText) {
        super(labelText);
        this.parentComponent = parentComponent;
        setupComponent();
    }

    /**
     * Creates a new GitHub App installation label with default text.
     *
     * @param parentComponent The parent component, used to determine window ancestry for browser operations
     */
    public GitHubAppInstallLabel(Component parentComponent) {
        super(DEFAULT_LABEL_TEXT);
        this.parentComponent = parentComponent;
        setupComponent();
    }

    private void setupComponent() {
        setCursor(new Cursor(Cursor.HAND_CURSOR));

        // Use UIManager font as consistent base to avoid font size differences between contexts
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont != null) {
            setFont(baseFont.deriveFont(Font.PLAIN, baseFont.getSize() * 1.15f));
        }

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Environment.openInBrowser(
                            GITHUB_APP_INSTALL_URL, SwingUtilities.getWindowAncestor(parentComponent));
                } catch (Exception ex) {
                    logger.error("Failed to open GitHub App installation page", ex);
                }
            }
        });
    }
}
