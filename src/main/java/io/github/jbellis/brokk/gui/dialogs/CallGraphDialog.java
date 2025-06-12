package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.CallSite;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dialog for configuring call graph analysis with method selection and depth control
 */
public class CallGraphDialog extends JDialog {

    private final SymbolSelectionPanel selectionPanel;
    private final JButton okButton;
    private final JButton cancelButton;
    private final JSpinner depthSpinner;
    private final JLabel callSitesLabel;
    private final IAnalyzer analyzer;

    @Nullable
    private String selectedMethod = null;

    private int depth = 5;

    private boolean confirmed = false;

    @Nullable
    private Map<String, List<CallSite>> callGraph = null;

    private boolean isCallerGraph;

    public CallGraphDialog(Frame parent, IAnalyzer analyzer, String title, boolean isCallerGraph) {
        super(parent, title, true); // modal dialog
        assert parent != null;
        assert title != null;
        assert analyzer != null;
        
        this.analyzer = analyzer;
        this.isCallerGraph = isCallerGraph;

        var mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Create the symbol selection panel - limited to methods only
        selectionPanel = new SymbolSelectionPanel(analyzer, Set.of(CodeUnitType.FUNCTION));
        selectionPanel.addSymbolSelectionListener(this::updateCallGraph);

        // Create the depth panel with spinner
        var depthPanel = new JPanel(new GridBagLayout());
        depthPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 5);

        // First row - depth spinner
        var depthLabel = new JLabel("Depth: ");
        var spinnerModel = new SpinnerNumberModel(5, 1, 10, 1);
        depthSpinner = new JSpinner(spinnerModel);

        gbc.gridx = 0;
        gbc.gridy = 0;
        depthPanel.add(depthLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        depthPanel.add(depthSpinner, gbc);
        
        // Third row - help text
        var helpLabel = new JLabel("Maximum depth of the call graph");
        helpLabel.setFont(helpLabel.getFont().deriveFont(Font.ITALIC));
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(4, 0, 0, 0);
        depthPanel.add(helpLabel, gbc);

        // Add a vertical spacer
        var spacer = Box.createVerticalStrut(10);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        depthPanel.add(spacer, gbc);

        // Add call sites counter label
        callSitesLabel = new JLabel("Call sites: 0");
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        depthPanel.add(callSitesLabel, gbc);

        // Add components directly to the main panel
        mainPanel.add(selectionPanel, BorderLayout.NORTH);
        mainPanel.add(depthPanel, BorderLayout.WEST);

        // Buttons at the bottom
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Handle escape key to close dialog
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectedMethod = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        selectionPanel.getInputField().setToolTipText("Enter a method name and press Enter to confirm");

        // Listen to spinner changes
        spinnerModel.addChangeListener(e -> {
            depth = (Integer) depthSpinner.getValue();
            updateCallGraph();
        });

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Updates the call graph map when the method or depth changes
     */
    private void updateCallGraph() {
        String methodName = selectionPanel.getSymbolText();
        if (methodName.isEmpty()) {
            callGraph = null;
            updateCallSitesCount(0);
            return;
        }

        // Update the call graph map
        if (isCallerGraph) {
            callGraph = analyzer.getCallgraphTo(methodName, depth);
        } else {
            callGraph = analyzer.getCallgraphFrom(methodName, depth);
        }

        // Count total call sites
        int totalCallSites = 0;
        if (callGraph != null) {
            totalCallSites = callGraph.values().stream()
                    .mapToInt(List::size)
                    .sum();
        }
        
        updateCallSitesCount(totalCallSites);
    }
    
    /**
     * Updates the call sites count label
     */
    private void updateCallSitesCount(int count) {
        callSitesLabel.setText("Call sites: " + count);
    }

    /**
     * When OK is pressed, get the method and depth values.
     */
    private void doOk() {
        String typed = selectionPanel.getSymbolText();
        if (typed.isEmpty()) {
            selectedMethod = null;
        } else {
            selectedMethod = typed;
        }

        confirmed = true;
        depth = (Integer) depthSpinner.getValue();
        dispose();
    }

    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Return the selected method or null if none.
     */
    @Nullable
    public String getSelectedMethod() {
        return selectedMethod;
    }

    /**
     * Return the selected depth.
     */
    public int getDepth() {
        return depth;
    }
    
    /**
     * Return the call graph map (callers or callees)
     */
    @Nullable
    public Map<String, List<CallSite>> getCallGraph() {
        return callGraph;
    }
}
