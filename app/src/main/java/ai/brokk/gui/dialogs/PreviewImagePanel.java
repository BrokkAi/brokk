package ai.brokk.gui.dialogs;

import ai.brokk.analyzer.BrokkFile;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

public class PreviewImagePanel extends JPanel {
    @Nullable
    private final BrokkFile file;

    @Nullable
    private BufferedImage image;

    public PreviewImagePanel(@Nullable BrokkFile file) {
        super(new BorderLayout());
        this.file = file;
        loadImage();
        setupUI();
    }

    private void loadImage() {
        if (file != null) {
            try {
                image = ImageIO.read(file.absPath().toFile());
                if (image == null) {
                    JOptionPane.showMessageDialog(
                            this, "Could not read image file.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(
                        this, "Error loading image: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void setImage(Image image) {
        SwingUtilities.invokeLater(() -> {
            this.image = (BufferedImage) image;
            removeAll();
            setupUI();
            revalidate();
            repaint();
        });
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
     * Gets the BrokkFile associated with this preview panel.
     *
     * @return The BrokkFile, or null if this preview is not associated with a file
     */
    @Nullable
    public BrokkFile getFile() {
        return file;
    }

    /** Refreshes the image from disk if the file has changed. */
    public void refreshFromDisk() {
        if (file == null) {
            return;
        }

        try {
            // Check if file still exists
            if (!java.nio.file.Files.exists(file.absPath())) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                            this, "Image file has been deleted: " + file, "File Deleted", JOptionPane.WARNING_MESSAGE);
                });
                return;
            }

            // Reload the image
            var newImage = ImageIO.read(file.absPath().toFile());
            if (newImage == null) {
                return; // Could not read image, keep current one
            }

            // Update the displayed image
            setImage(newImage);

        } catch (IOException e) {
            // Silently fail - keep existing image displayed
        }
    }

}
