package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

public final class LoadingTextBox extends JPanel {

    private final Chrome chrome;
    private final JTextField textField;
    private final JLabel spinner;
    private final String placeholder;
    private boolean showingHint;
    private boolean internalChange = false; // Flag to track internal text changes
    private final Color hintColor = Color.GRAY;
    private final Color defaultColor;

    private final String idleTooltip;

    public LoadingTextBox(String placeholder, int columns, Chrome chrome) {
        super(new BorderLayout(2, 0)); // tiny H_GAP between field and spinner
        this.chrome = chrome;
        this.placeholder = placeholder;

        this.textField = new JTextField(columns);
        this.defaultColor = textField.getForeground();
        this.spinner = new JLabel();
        spinner.setVisible(false); // hidden by default

        add(textField, BorderLayout.CENTER);
        add(spinner, BorderLayout.EAST);

        this.idleTooltip = textField.getToolTipText();
        showPlaceholder();

        textField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (showingHint) {
                    hidePlaceholder();
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (textField.getText().isEmpty()) {
                    showPlaceholder();
                }
            }
        });
    }

    private void showPlaceholder() {
        internalChange = true;
        textField.setText(placeholder);
        textField.setForeground(hintColor);
        showingHint = true;
        internalChange = false;
    }

    private void hidePlaceholder() {
        internalChange = true;
        textField.setText("");
        textField.setForeground(defaultColor);
        showingHint = false;
        internalChange = false;
    }

    public void setLoading(boolean loading, String busyTooltip) {
        assert SwingUtilities.isEventDispatchThread() :
               "LoadingTextBox.setLoading must be called on the EDT";

        if (loading) {
            spinner.setIcon(SpinnerIconUtil.getSpinner(chrome, false));
            spinner.setVisible(true);
            textField.setToolTipText(busyTooltip != null ? busyTooltip : "Searching...");
        } else {
            spinner.setVisible(false);
            textField.setToolTipText(idleTooltip);
        }
    }

    public String getText() {
        return showingHint ? "" : textField.getText();
    }

    public void setText(String txt) {
        if (txt == null || txt.isEmpty()) {
            showPlaceholder();
        } else {
            hidePlaceholder(); // Ensure hint is not showing and color is correct
            textField.setText(txt);
        }
    }

    public void addActionListener(ActionListener l) { textField.addActionListener(l); }

    public void addDocumentListener(DocumentListener l) {
        textField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!internalChange) {
                    l.insertUpdate(e);
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!internalChange) {
                    l.removeUpdate(e);
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                if (!internalChange) {
                    l.changedUpdate(e);
                }
            }
        });
    }

    public JTextField asTextField() { return textField; }
}
