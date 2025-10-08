package io.github.jbellis.brokk.gui.visualize;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Placeholder dialog for the File Co-Change visualization.
 * Wired from Tools -> Visualize menu item.
 */
public class CoChangeGraphDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CoChangeGraphDialog.class);

    private final CoChangeGraphPanel graphPanel;

    public CoChangeGraphDialog(Frame owner, Chrome chrome) {
        super(owner, "Visualize File Co-Changes", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // Content area
        var contentPanel = new JPanel(new BorderLayout());
        this.graphPanel = new CoChangeGraphPanel();
        contentPanel.add(graphPanel, BorderLayout.CENTER);
        add(contentPanel, BorderLayout.CENTER);

        // Buttons panel (primary Done + Cancel as per style guide)
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));

        var doneButton = new MaterialButton("Done");
        SwingUtil.applyPrimaryButtonStyle(doneButton);
        doneButton.addActionListener(e -> dispose());

        var cancelButton = new MaterialButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        buttonsPanel.add(doneButton);
        buttonsPanel.add(cancelButton);

        add(buttonsPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(960, 640));
        pack();
        setLocationRelativeTo(owner);

        logger.debug("CoChangeGraphDialog initialized");
    }
}
