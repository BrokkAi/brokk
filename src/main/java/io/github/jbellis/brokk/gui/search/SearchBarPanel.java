package io.github.jbellis.brokk.gui.search;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.Objects;

/**
 * A reusable search bar panel that can be used with any component implementing SearchCallback.
 */
public class SearchBarPanel extends JPanel {
    private static final String CP_FOREGROUND = "SearchBar.foreground";
    
    private JTextField searchField;
    private JLabel searchResult;
    private Timer timer;
    private final SearchCallback searchCallback;
    private final boolean showCaseSensitive;
    private final boolean showNavigation;
    private JCheckBox caseSensitiveCheckBox;
    private final int minimumSearchChars; // Default minimum characters to trigger search
    private boolean validSearchActive = false; // Track if a valid search is currently active
    
    /**
     * Creates a search bar panel with the given callback.
     * 
     * @param searchCallback The callback to handle search operations
     */
    public SearchBarPanel(SearchCallback searchCallback) {
        this(searchCallback, true, true);
    }
    
    public SearchBarPanel(SearchCallback searchCallback, boolean showCaseSensitive) {
        this(searchCallback, showCaseSensitive, true);
    }
    
    public SearchBarPanel(SearchCallback searchCallback, boolean showCaseSensitive, boolean showNavigation) {
        this(searchCallback, showCaseSensitive, showNavigation, 1);
    }
    
    public SearchBarPanel(SearchCallback searchCallback, boolean showCaseSensitive, boolean showNavigation, int minimumSearchChars) {
        this.searchCallback = searchCallback;
        this.showCaseSensitive = showCaseSensitive;
        this.showNavigation = showNavigation;
        this.minimumSearchChars = Math.max(1, minimumSearchChars); // Ensure at least 1 character
        init();
    }
    
    private void init() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        
        // Search field row
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        searchField = new JTextField(20);
        
        // Timer for delayed search
        timer = new Timer(300, e -> performSearch());
        timer.setRepeats(false);
        
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                restartTimerIfNeeded();
            }
            
            @Override
            public void removeUpdate(DocumentEvent e) {
                restartTimerIfNeeded();
            }
            
            @Override
            public void changedUpdate(DocumentEvent e) {
                restartTimerIfNeeded();
            }
            
            private void restartTimerIfNeeded() {
                String searchText = searchField.getText();
                
                // Only restart timer if:
                // 1. Text is long enough to trigger a search, OR
                // 2. We have an active search that might need to be cleared
                if (searchText.length() >= minimumSearchChars || validSearchActive) {
                    timer.restart();
                }
            }
        });
        
        searchField.addActionListener(e -> performSearch());
        
        searchPanel.add(new JLabel("Find:"));
        searchPanel.add(searchField);
        
        // Case sensitive checkbox (optional)
        if (showCaseSensitive) {
            caseSensitiveCheckBox = new JCheckBox("Case sensitive");
            caseSensitiveCheckBox.addActionListener(e -> performSearch());
            searchPanel.add(caseSensitiveCheckBox);
        }
        
        // Add components to main panel
        add(Box.createVerticalStrut(5));
        add(searchPanel);
        
        if (showNavigation) {
            // Buttons row
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
            
            JButton previousButton = new JButton("Previous");
            if (hasIcon("/images/prev.png")) {
                previousButton.setIcon(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/prev.png"))));
            }
            previousButton.addActionListener(getPreviousAction());
            initButton(previousButton);
            
            JButton nextButton = new JButton("Next");
            if (hasIcon("/images/next.png")) {
                nextButton.setIcon(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/next.png"))));
            }
            nextButton.addActionListener(getNextAction());
            initButton(nextButton);
            
            JButton clearButton = new JButton("Clear");
            clearButton.addActionListener(getClearAction());
            initButton(clearButton);
            
            buttonPanel.add(previousButton);
            buttonPanel.add(Box.createHorizontalStrut(5));
            buttonPanel.add(nextButton);
            buttonPanel.add(Box.createHorizontalStrut(5));
            buttonPanel.add(clearButton);
            
            add(Box.createVerticalStrut(5));
            add(buttonPanel);
        }
        
        // Search result label
        searchResult = new JLabel();
        
        if (showNavigation) {
            add(Box.createVerticalStrut(5));
            add(searchResult);
        }
    }
    
    private boolean hasIcon(String path) {
        return getClass().getResource(path) != null;
    }
    
    private void initButton(AbstractButton button) {
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(0, 5, 0, 5));
    }
    
    public SearchCommand getCommand() {
        assert SwingUtilities.isEventDispatchThread();
        boolean caseSensitive = caseSensitiveCheckBox != null && caseSensitiveCheckBox.isSelected();
        return new SearchCommand(searchField.getText(), caseSensitive);
    }
    
    public String getSearchText() {
        assert SwingUtilities.isEventDispatchThread();
        return searchField.getText();
    }
    
    public void setSearchText(String text) {
        assert SwingUtilities.isEventDispatchThread();
        searchField.setText(text);
    }
    
    public void focusSearchField() {
        assert SwingUtilities.isEventDispatchThread();
        searchField.requestFocusInWindow();
    }
    
    public void findNext() {
        assert SwingUtilities.isEventDispatchThread();
        searchCallback.goToNextResult();
        updateNavigationResults();
    }
    
    public void findPrevious() {
        assert SwingUtilities.isEventDispatchThread();
        searchCallback.goToPreviousResult();
        updateNavigationResults();
    }
    
    public void clearSearch() {
        assert SwingUtilities.isEventDispatchThread();
        searchField.setText("");
        if (validSearchActive) {
            searchCallback.stopSearch();
            validSearchActive = false;
        }
        searchResult.setIcon(null);
        searchResult.setText("");
    }
    
    public void performSearch() {
        assert SwingUtilities.isEventDispatchThread();
        String searchText = searchField.getText();
        
        // Only perform search if the text meets minimum length requirement
        if (searchText.length() >= minimumSearchChars) {
            SearchResults results = searchCallback.performSearch(getCommand());
            updateSearchResults(results);
            validSearchActive = true;
        } else if (!searchText.isEmpty()) {
            if (validSearchActive) {
                searchCallback.stopSearch();
                validSearchActive = false;
            }
            searchResult.setText("Enter at least " + minimumSearchChars + " characters to search");
            // Reset any error color
            Color originalColor = (Color) searchField.getClientProperty(CP_FOREGROUND);
            if (originalColor != null) {
                searchField.setForeground(originalColor);
                searchField.putClientProperty(CP_FOREGROUND, null);
            }
        } else {
            // Empty text - clear search only if a valid search was active
            if (validSearchActive) {
                searchCallback.stopSearch();
                validSearchActive = false;
            }
            searchResult.setText("");
        }
    }
    
    public void updateSearchResults(SearchResults results) {
        assert SwingUtilities.isEventDispatchThread();
        boolean notFound = results == null || results.isEmpty();
        String searchText = searchField.getText();
        
        if (notFound && !searchText.isEmpty()) {
            // Set error state
            if (!Objects.equals(searchField.getForeground(), Color.red)) {
                searchField.putClientProperty(CP_FOREGROUND, searchField.getForeground());
                searchField.setForeground(Color.red);
            }
            
            if (hasIcon("/images/result.png")) {
                searchResult.setIcon(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/result.png"))));
            }
            searchResult.setText("Phrase not found");
        } else {
            // Reset to normal state
            Color originalColor = (Color) searchField.getClientProperty(CP_FOREGROUND);
            if (originalColor != null) {
                searchField.setForeground(originalColor);
                searchField.putClientProperty(CP_FOREGROUND, null);
            }
            
            if (results != null && results.hasMatches()) {
                searchResult.setIcon(null);
                searchResult.setText(String.format("%d of %d", results.getCurrentMatch(), results.getTotalMatches()));
            } else { // This combined else correctly handles cases where results has no matches or search text is empty
                searchResult.setIcon(null);
                searchResult.setText("");
            }
        }
    }
    
    private ActionListener getClearAction() {
        return ae -> clearSearch();
    }
    
    private ActionListener getPreviousAction() {
        return ae -> findPrevious();
    }
    
    private ActionListener getNextAction() {
        return ae -> findNext();
    }
    
    private void updateNavigationResults() {
        // For callbacks that support getCurrentResults, update the display
        if (searchCallback instanceof MarkdownPanelSearchCallback markdownCallback) {
            SearchResults results = markdownCallback.getCurrentResults();
            updateSearchResults(results);
        }
    }
    
    /**
     * Registers Ctrl/Cmd+F shortcut to focus the search field.
     */
    public void registerSearchFocusShortcut(JComponent targetComponent) {
        assert SwingUtilities.isEventDispatchThread();
        KeyStroke focusSearchKey = KeyStroke.getKeyStroke(KeyEvent.VK_F, 
            java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        
        targetComponent.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(focusSearchKey, "focusSearch");
        targetComponent.getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                focusSearchField();
            }
        });
    }
    
}
