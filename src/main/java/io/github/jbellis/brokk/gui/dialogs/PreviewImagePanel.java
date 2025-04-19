package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;

public class PreviewImagePanel extends JPanel {
    private final ContextManager contextManager;
    private final BrokkFile file;
    private final GuiTheme guiTheme;
    private BufferedImage image;

    public PreviewImagePanel(ContextManager contextManager, BrokkFile file, GuiTheme guiTheme) {
        super(new BorderLayout());
        this.contextManager = contextManager;
        this.file = file;
        this.guiTheme = guiTheme;
        loadImage();
        setupUI();
        registerEscapeKey();
    }

    private void loadImage() {
            if (file != null) {
                try {
                    image = ImageIO.read(file.absPath().toFile());
                    if (image == null) {
                        JOptionPane.showMessageDialog(this, "Could not read image file.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(this, "Error loading image: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        public void setImage(Image image) {
            this.image = (BufferedImage)image;
            removeAll();
            setupUI();
            revalidate();
            repaint();
        }

    private void setupUI() {
        if (image != null) {
            JLabel imageLabel = new JLabel(new ImageIcon(image));
            JScrollPane scrollPane = new JScrollPane(imageLabel);
            add(scrollPane, BorderLayout.CENTER);
        } else {
            JLabel errorLabel = new JLabel("Image could not be displayed.");
            errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
            add(errorLabel, BorderLayout.CENTER);
        }
    }

    /**
     * Displays a non-modal preview dialog for the given image file.
     *
     * @param parentFrame    The parent frame.
     * @param contextManager The context manager.
     * @param file           The BrokkFile to preview.
     * @param guiTheme       The GUI theme manager.
     */
    public static void showInFrame(JFrame parentFrame, ContextManager contextManager, BrokkFile file, GuiTheme guiTheme) {
        try {
            PreviewImagePanel previewPanel = new PreviewImagePanel(contextManager, file, guiTheme);
            showFrame(contextManager, file.toString(), previewPanel);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parentFrame, "Error displaying image: " + ex.getMessage(), "Display Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void showFrame(ContextManager contextManager, String title, PreviewImagePanel previewPanel) {
        JFrame frame = Chrome.newFrame(title);
            frame.setContentPane(previewPanel);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE); // Dispose frame on close

        var project = contextManager.getProject();
        assert project != null;
        var storedBounds = project.getPreviewWindowBounds();
        if (storedBounds != null) {
            frame.setBounds(storedBounds);
        }

        // Add listener to save bounds
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(frame); // Save JFrame bounds
            }
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(frame); // Save JFrame bounds
            }
        });

        frame.setVisible(true);
    }

    /**
     * Registers ESC key to close the preview panel
     */
    private void registerEscapeKey() {
        // Register ESC key to close the dialog
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

        // Add ESC handler to panel to close window
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escapeKeyStroke, "closePreview");
        getActionMap().put("closePreview", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Window window = SwingUtilities.getWindowAncestor(PreviewImagePanel.this);
                if (window != null) {
                    window.dispose();
                }
            }
        });
    }
}
