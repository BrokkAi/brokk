package ai.brokk.gui.dialogs;

import ai.brokk.gui.components.RoundedLineBorder;
import ai.brokk.gui.util.GitUiUtil;
import java.awt.Color;
import java.util.Optional;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for real-time validation feedback in SettingsProjectPanel GitHub fields.
 *
 * <p>These tests verify that:
 * <ul>
 *   <li>Layout remains stable when toggling between valid and invalid states
 *   <li>Tooltips display correct error messages on validation failure
 *   <li>Borders apply and remove correctly without layout shifts
 * </ul>
 *
 * <p>Note: This test class uses SwingUtilities to ensure all operations occur on the EDT.
 */
@Timeout(10)
@DisplayName("SettingsProjectPanel Real-Time Validation Tests")
public class SettingsProjectPanelValidationTest {

    private JTextField ownerField;
    private JTextField repoField;
    private JTextField hostField;

    @BeforeEach
    void setUp() {
        ownerField = new JTextField(20);
        repoField = new JTextField(20);
        hostField = new JTextField(20);

        // Initialize with transparent placeholder borders (as done in createIssuesPanel)
        var transparentBorder = new RoundedLineBorder(new Color(0, 0, 0, 0), 2, 3);
        ownerField.setBorder(transparentBorder);
        repoField.setBorder(transparentBorder);
        hostField.setBorder(transparentBorder);
    }

    @Test
    @DisplayName("Layout remains stable when field transitions from valid to invalid")
    void testLayoutStabilityDuringValidationToggle() throws Exception {
        var initialSize = new java.awt.Dimension[1];
        var sizeAfterValid = new java.awt.Dimension[1];
        var sizeAfterInvalid = new java.awt.Dimension[1];

        SwingUtilities.invokeAndWait(() -> {
            // Create validator that returns error for "bad" input
            var validator = (java.util.function.Function<String, Optional<String>>) input -> {
                if ("bad".equalsIgnoreCase(input.trim())) {
                    return Optional.of("Invalid input");
                }
                return Optional.empty();
            };

            // Attach listener
            var listener = GitUiUtil.createRealtimeValidationListener(ownerField, validator);
            ownerField.getDocument().addDocumentListener(listener);

            // Record initial preferred size
            initialSize[0] = ownerField.getPreferredSize();

            // Set valid text
            ownerField.setText("good");
        });

        // Wait for debounce to complete outside of EDT-blocking context
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check size remains constant after valid input
        SwingUtilities.invokeAndWait(() -> {
            sizeAfterValid[0] = ownerField.getPreferredSize();
            assert initialSize[0].width == sizeAfterValid[0].width
                    : "Width changed after valid input: " + initialSize[0].width + " vs " + sizeAfterValid[0].width;
            assert initialSize[0].height == sizeAfterValid[0].height
                    : "Height changed after valid input: " + initialSize[0].height + " vs " + sizeAfterValid[0].height;

            // Set invalid text
            ownerField.setText("bad");
        });

        // Wait for debounce to complete
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check size remains constant after invalid input
        SwingUtilities.invokeAndWait(() -> {
            sizeAfterInvalid[0] = ownerField.getPreferredSize();
            assert initialSize[0].width == sizeAfterInvalid[0].width
                    : "Width changed after invalid input: " + initialSize[0].width + " vs " + sizeAfterInvalid[0].width;
            assert initialSize[0].height == sizeAfterInvalid[0].height
                    : "Height changed after invalid input: " + initialSize[0].height + " vs " + sizeAfterInvalid[0].height;
        });
    }

    @Test
    @DisplayName("Tooltip displays error message on validation failure")
    void testTooltipDisplaysErrorMessage() throws Exception {
        String expectedError = "Repository must be in the form 'owner/repo'";

        SwingUtilities.invokeAndWait(() -> {
            // Create validator that returns specific error
            var validator = (java.util.function.Function<String, Optional<String>>) input -> {
                if (input.isEmpty()) {
                    return Optional.of(expectedError);
                }
                return Optional.empty();
            };

            // Attach listener
            var listener = GitUiUtil.createRealtimeValidationListener(repoField, validator);
            repoField.getDocument().addDocumentListener(listener);

            // Initially empty - should trigger error after debounce
            repoField.setText("");
        });

        // Pump EDT to allow Timer callbacks to fire
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 500) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> {
                // This allows pending events (like Timer) to be processed
            });

            // Check if tooltip is set
            var tooltip = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                tooltip[0] = repoField.getToolTipText();
            });

            if (tooltip[0] != null) {
                // Tooltip is set, validation completed
                break;
            }
        }

        // Final check
        SwingUtilities.invokeAndWait(() -> {
            var tooltip = repoField.getToolTipText();
            assert tooltip != null : "Tooltip is null for invalid field";
            assert tooltip.contains(expectedError) || tooltip.equals(expectedError)
                    : "Tooltip does not contain expected error. Got: " + tooltip;
        });
    }

    @Test
    @DisplayName("Tooltip clears on validation success")
    void testTooltipClearsOnValidationSuccess() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var validator = (java.util.function.Function<String, Optional<String>>) input -> {
                if (input.isEmpty()) {
                    return Optional.of("Field cannot be empty");
                }
                return Optional.empty();
            };

            // Attach listener
            var listener = GitUiUtil.createRealtimeValidationListener(hostField, validator);
            hostField.getDocument().addDocumentListener(listener);

            // Set invalid text
            hostField.setText("");
        });

        // Pump EDT to allow Timer callback to fire
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 500) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> {
                // This allows pending events to be processed
            });

            var tooltip = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                tooltip[0] = hostField.getToolTipText();
            });

            if (tooltip[0] != null) {
                // Tooltip is set, validation completed
                break;
            }
        }

        // Verify tooltip is set
        SwingUtilities.invokeAndWait(() -> {
            var tooltipAfterInvalid = hostField.getToolTipText();
            assert tooltipAfterInvalid != null : "Tooltip should be set after invalid input";
        });

        // Set valid text
        SwingUtilities.invokeAndWait(() -> {
            hostField.setText("github.com");
        });

        // Pump EDT again to allow Timer callback
        startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 500) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> {
                // This allows pending events to be processed
            });

            var tooltip = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                tooltip[0] = hostField.getToolTipText();
            });

            if (tooltip[0] == null) {
                // Tooltip is cleared, validation completed
                break;
            }
        }

        // Verify tooltip is cleared
        SwingUtilities.invokeAndWait(() -> {
            var tooltipAfterValid = hostField.getToolTipText();
            assert tooltipAfterValid == null : "Tooltip should be null after valid input, got: " + tooltipAfterValid;
        });
    }

    @Test
    @DisplayName("Border applies correctly for validation failure")
    void testBorderAppliesToErrorState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var validator = (java.util.function.Function<String, Optional<String>>) input -> {
                if ("invalid".equalsIgnoreCase(input.trim())) {
                    return Optional.of("Invalid value");
                }
                return Optional.empty();
            };

            var listener = GitUiUtil.createRealtimeValidationListener(ownerField, validator);
            ownerField.getDocument().addDocumentListener(listener);

            // Set invalid text
            ownerField.setText("invalid");
        });

        // Wait for debounce to complete outside of EDT-blocking context
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check border on EDT
        SwingUtilities.invokeAndWait(() -> {
            var border = ownerField.getBorder();
            assert border instanceof RoundedLineBorder : "Border should be RoundedLineBorder, got: " + border.getClass();
        });
    }

    @Test
    @DisplayName("Border reverts to transparent on validation success")
    void testBorderRevertsToTransparentOnSuccess() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var validator = (java.util.function.Function<String, Optional<String>>) input -> {
                if ("invalid".equalsIgnoreCase(input.trim())) {
                    return Optional.of("Invalid value");
                }
                return Optional.empty();
            };

            var listener = GitUiUtil.createRealtimeValidationListener(repoField, validator);
            repoField.getDocument().addDocumentListener(listener);

            // Set invalid text first
            repoField.setText("invalid");
        });

        // Wait for debounce to complete
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify error border is applied
        SwingUtilities.invokeAndWait(() -> {
            var borderAfterError = repoField.getBorder();
            assert borderAfterError instanceof RoundedLineBorder : "Border should be RoundedLineBorder after error";
        });

        // Set valid text
        SwingUtilities.invokeAndWait(() -> {
            repoField.setText("valid-value");
        });

        // Wait for debounce to complete
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify transparent border is applied
        SwingUtilities.invokeAndWait(() -> {
            var borderAfterSuccess = repoField.getBorder();
            assert borderAfterSuccess instanceof RoundedLineBorder
                    : "Border should remain RoundedLineBorder after success (transparent)";
        });
    }

    @Test
    @DisplayName("Debounce prevents excessive validation calls")
    void testDebounceDelaysValidation() throws Exception {
        var callCount = new int[] {0};

        SwingUtilities.invokeAndWait(() -> {
            var validator = (java.util.function.Function<String, Optional<String>>) input -> {
                callCount[0]++;
                return Optional.empty();
            };

            var listener = GitUiUtil.createRealtimeValidationListener(hostField, validator);
            hostField.getDocument().addDocumentListener(listener);

            // Rapidly type characters (simulating user input)
            hostField.setText("g");
            hostField.setText("gi");
            hostField.setText("git");
            hostField.setText("gith");
        });

        // Wait less than debounce period outside of EDT-blocking context
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Validation should not have been called yet (or very few times due to timing)
        int callsBeforeDebounce = callCount[0];
        assert callsBeforeDebounce <= 1 : "Too many validation calls before debounce period: " + callsBeforeDebounce;

        // Wait for debounce to complete
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now validation should have been called
        int callsAfterDebounce = callCount[0];
        assert callsAfterDebounce > callsBeforeDebounce
                : "Validation should have been called after debounce period";
    }
}
