package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;

import javax.swing.*;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionListener;

public final class LoadingTextBox extends JPanel {

    private final Chrome chrome;
    private final JTextField textField;
    private final JLabel spinner;

    private final String idleTooltip;

    public LoadingTextBox(String placeholder, int columns, Chrome chrome) {
        super(new BorderLayout(2, 0)); // tiny H_GAP between field and spinner
        this.chrome = chrome;

        this.textField = new JTextField(placeholder, columns);
        this.spinner = new JLabel();
        spinner.setVisible(false); // hidden by default

        add(textField, BorderLayout.CENTER);
        add(spinner, BorderLayout.EAST);

        this.idleTooltip = textField.getToolTipText();
    }

    public void setLoading(boolean loading, String busyTooltip) {
        assert SwingUtilities.isEventDispatchThread() :
               "LoadingTextBox.setLoading must be called on the EDT";

        if (loading) {
            spinner.setIcon(SpinnerIconUtil.getSpinner(chrome));
            spinner.setVisible(true);
            textField.setEnabled(false);
            textField.setToolTipText(busyTooltip != null ? busyTooltip : "Searching...");
        } else {
            spinner.setVisible(false);
            textField.setEnabled(true);
            textField.setToolTipText(idleTooltip);
        }
    }

    public String getText() { return textField.getText(); }
    public void setText(String txt) { textField.setText(txt); }

    public void addActionListener(ActionListener l) { textField.addActionListener(l); }
    public void addDocumentListener(DocumentListener l) { textField.getDocument().addDocumentListener(l); }

    public JTextField asTextField() { return textField; }
}
