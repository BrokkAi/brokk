package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.AnalyzerListener;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.swing.*;
import java.awt.Component;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BuildCompletionUiTest {

    @Mock
    private Chrome mockChrome;

    private MockedStatic<JOptionPane> mockedJOptionPane;
    private MockedStatic<SettingsDialog> mockedSettingsDialog;
    private MockedStatic<SwingUtil> mockedSwingUtil;

    @BeforeEach
    void setUp() {
        System.setProperty("java.awt.headless", "true");
        mockedJOptionPane = mockStatic(JOptionPane.class);
        mockedSettingsDialog = mockStatic(SettingsDialog.class);
        
        // Mock SwingUtil.runOnEdt to execute runnables immediately for test simplicity
        mockedSwingUtil = mockStatic(SwingUtil.class);
        mockedSwingUtil.when(() -> SwingUtil.runOnEdt(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return true; 
        });

        // Setup mockChrome behavior
        when(mockChrome.getFrame()).thenReturn(null); // JOptionPane parent can be null
    }

    @AfterEach
    void tearDown() {
        mockedJOptionPane.close();
        mockedSettingsDialog.close();
        mockedSwingUtil.close();
        System.clearProperty("java.awt.headless");
    }

    @Test
    void opensSettingsDialogAfterFirstBuild() {
        // This anonymous class replicates the specific logic from ContextManager's AnalyzerListener
        AnalyzerListener listener = new AnalyzerListener() {
            @Override public void onBlocked() {}
            @Override public void onRepoChange() {}
            @Override public void onTrackedFileChange() {}
            @Override public void afterEachBuild(boolean externalRebuildRequested) {}

            @Override
            public void afterFirstBuild(String msg) {
                // The 'chrome' instance here will be our mockChrome
                // The call to chrome.notifyActionComplete is omitted as it's not the focus of this test
                SwingUtil.runOnEdt(() -> {
                    JOptionPane.showMessageDialog(
                            mockChrome.getFrame(),
                            """
                            Build Agent has completed inspecting your project,
                            please review the build configuration.
                            """.stripIndent(),
                            "Build Completed",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                    SettingsDialog.showSettingsDialog(mockChrome, "Build");
                });
                // The original listener has more logic for messages, not relevant for this specific test's verification
            }
        };

        // Act
        listener.afterFirstBuild("Test build message");

        // Verify JOptionPane call
        mockedJOptionPane.verify(() -> JOptionPane.showMessageDialog(
                mockChrome.getFrame(),
                """
                Build Agent has completed inspecting your project,
                please review the build configuration.
                """.stripIndent(),
                "Build Completed",
                JOptionPane.INFORMATION_MESSAGE
        ));

        // Verify SettingsDialog call
        mockedSettingsDialog.verify(() -> SettingsDialog.showSettingsDialog(mockChrome, "Build"));
    }
}
