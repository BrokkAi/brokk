package ai.brokk.gui.components;

import ai.brokk.gui.Chrome;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public final class LoadingTextBox extends JPanel {

    private final Chrome chrome;
    private final JTextField textField;
    private final JLabel spinner;
    private final String placeholder;
    private boolean showingHint;
    private boolean internalChange = false;
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
        textField.setForeground(isEnabled() ? hintColor : resolveDisabledForeground());
        showingHint = true;
        internalChange = false;
    }

    private void hidePlaceholder() {
        internalChange = true;
        textField.setText("");
        textField.setForeground(isEnabled() ? defaultColor : resolveDisabledForeground());
        showingHint = false;
        internalChange = false;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        textField.setEnabled(enabled);
        spinner.setEnabled(enabled);

        if (enabled) {
            if (showingHint) {
                textField.setForeground(hintColor);
            } else {
                textField.setForeground(defaultColor);
            }
        } else {
            textField.setForeground(resolveDisabledForeground());
        }

        repaint();
    }

    private Color resolveDisabledForeground() {
        var disabledFg = UIManager.getColor("TextField.inactiveForeground");
        if (disabledFg == null) disabledFg = textField.getDisabledTextColor();
        if (disabledFg == null) disabledFg = UIManager.getColor("Label.disabledForeground");
        if (disabledFg == null) disabledFg = textField.getForeground().darker();
        return disabledFg;
    }

    public void setLoading(boolean loading, String busyTooltip) {
        assert SwingUtilities.isEventDispatchThread() : "LoadingTextBox.setLoading must be called on the EDT";

        if (loading) {
            spinner.setIcon(SpinnerIconUtil.getSpinner(chrome, false));
            spinner.setVisible(true);
            textField.setToolTipText(busyTooltip);
        } else {
            spinner.setVisible(false);
            textField.setToolTipText(idleTooltip);
        }
    }

    public String getText() {
        return showingHint ? "" : textField.getText();
    }

    public void setText(String txt) {
        if (txt.isEmpty()) {
            showPlaceholder();
        } else {
            hidePlaceholder(); // Ensure hint is not showing and color is correct
            textField.setText(txt);
        }
    }

    public void addActionListener(ActionListener l) {
        textField.addActionListener(l);
    }

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

    public JTextField asTextField() {
        return textField;
    }
}
