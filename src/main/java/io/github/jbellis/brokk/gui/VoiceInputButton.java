package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A button that captures voice input from the microphone and transcribes it to a text area.
 */
public class VoiceInputButton extends JButton {
    private static final Logger logger = LogManager.getLogger(VoiceInputButton.class);

    private final JTextArea targetTextArea;
    private final ContextManager contextManager;
    private final Consumer<String> onError;
    private final Runnable onRecordingStart;
    private final Future<Set<String>> customSymbolsFuture;

    // For STT (mic) usage
    private volatile TargetDataLine micLine = null;
    private volatile ByteArrayOutputStream micBuffer = null;
    private volatile Thread micCaptureThread = null;
    private ImageIcon micOnIcon;
    private ImageIcon micOffIcon;

    /**
     * Creates a new voice input button.
     *
     * @param targetTextArea      the text area where transcribed text will be placed
     * @param contextManager      the context manager for speech-to-text processing
     * @param onRecordingStart    callback when recording starts
     * @param customSymbolsFuture Optional Future providing a set of symbols to prioritize for transcription hints. Can be null.
     * @param onError             callback for error handling
     */
    public VoiceInputButton(JTextArea targetTextArea,
                            ContextManager contextManager,
                            Runnable onRecordingStart,
                            Future<Set<String>> customSymbolsFuture,
                            Consumer<String> onError)
    {
        assert targetTextArea != null;
        assert onRecordingStart != null;
        assert onError != null;

        this.targetTextArea = targetTextArea;
        this.contextManager = contextManager;
        this.onRecordingStart = onRecordingStart;
        this.onError = onError;
        this.customSymbolsFuture = customSymbolsFuture;

        // Determine standard button height to make this button square
        var referenceButton = new JButton(" "); // Use a single space or "Hg" for height calculation
        int normalButtonHeight = referenceButton.getPreferredSize().height;

        // Determine appropriate icon size, leaving some padding similar to default button margins
        Insets buttonMargin = UIManager.getInsets("Button.margin");
        if (buttonMargin == null) {
            // Fallback if Look and Feel doesn't provide Button.margin
            // These values are typical for many L&Fs
            buttonMargin = new Insets(2, 14, 2, 14);
        }
        // Calculate icon size to fit within button height considering vertical margins
        int iconDisplaySize = normalButtonHeight - (buttonMargin.top + buttonMargin.bottom);
        iconDisplaySize = Math.max(8, iconDisplaySize); // Ensure a minimum practical size

        // Load mic icons
        try {
            micOnIcon = new ImageIcon(getClass().getResource("/mic-on.png"));
            micOffIcon = new ImageIcon(getClass().getResource("/mic-off.png"));
            // Scale icons to fit the dynamic button size
            micOnIcon = new ImageIcon(micOnIcon.getImage().getScaledInstance(iconDisplaySize, iconDisplaySize, Image.SCALE_SMOOTH));
            micOffIcon = new ImageIcon(micOffIcon.getImage().getScaledInstance(iconDisplaySize, iconDisplaySize, Image.SCALE_SMOOTH));
            logger.trace("Successfully loaded and scaled mic icons to {}x{}", iconDisplaySize, iconDisplaySize);
        } catch (Exception e) {
            logger.warn("Failed to load mic icons", e);
            // We'll fall back to text if icons can't be loaded
            micOnIcon = null;
            micOffIcon = null;
        }

        // Set default appearance
        if (micOffIcon != null) {
            setIcon(micOffIcon);
        } else {
            setText("Mic"); // Fallback text if icons fail to load
        }

        Dimension buttonSize = new Dimension(normalButtonHeight, normalButtonHeight);
        setPreferredSize(buttonSize);
        setMinimumSize(buttonSize);
        setMaximumSize(buttonSize);
        setMargin(new Insets(0, 0, 0, 0)); // Remove default margins to center icon better if it's the only content

        // Track recording state
        putClientProperty("isRecording", false);

        // Configure the toggle behavior
        addActionListener(e -> {
            boolean isRecording = (boolean) getClientProperty("isRecording");
            if (isRecording) {
                // If recording, stop and transcribe
                stopMicCaptureAndTranscribe();
                putClientProperty("isRecording", false);
            } else {
                // Otherwise start recording
                startMicCapture();
                putClientProperty("isRecording", true);
            }
        });

        // Enable the button only if a context manager is available (needed for transcription)
        model.setEnabled(contextManager != null);
    }

    /**
     * Convenience constructor without custom symbols.
     *
     * @param targetTextArea   the text area where transcribed text will be placed
     * @param contextManager   the context manager for speech-to-text processing
     * @param onRecordingStart callback when recording starts
     * @param onError          callback for error handling
     */
    public VoiceInputButton(JTextArea targetTextArea,
                            ContextManager contextManager,
                            Runnable onRecordingStart,
                            Consumer<String> onError)
    {
        this(targetTextArea, contextManager, onRecordingStart, null, onError);
    }

    /**
     * Starts capturing audio from the default microphone to micBuffer on a background thread.
     */
    private void startMicCapture() {
        try {
            // disable input field while capturing
            targetTextArea.setEnabled(false);

            // Change icon to mic-on
            if (micOnIcon != null) {
                setIcon(micOnIcon);
            }

            AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, true);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            micLine = (TargetDataLine) AudioSystem.getLine(info);
            micLine.open(format);
            micLine.start();

            micBuffer = new ByteArrayOutputStream();
            micCaptureThread = new Thread(() -> {
                var data = new byte[4096];
                while (micLine != null && micLine.isOpen()) {
                    int bytesRead = micLine.read(data, 0, data.length);
                    if (bytesRead > 0) {
                        synchronized (micBuffer) {
                            micBuffer.write(data, 0, bytesRead);
                        }
                    }
                }
            }, "mic-capture-thread");
            micCaptureThread.start();
            // Notify that recording has started
            onRecordingStart.run();
        } catch (Exception ex) {
            logger.error("Failed to start mic capture", ex);
            onError.accept("Error starting mic capture: " + ex.getMessage());
            targetTextArea.setEnabled(true);
        }
    }

    /**
     * Stops capturing and sends to STT on a background thread.
     */
    private void stopMicCaptureAndTranscribe() {
        assert contextManager != null;

        // stop capturing
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }
        micLine = null;

        // Change icon back to mic-off and restore background
        if (micOffIcon != null) {
            setIcon(micOffIcon);
        }
        setBackground(null); // Return to default button background
        contextManager.getIo().actionComplete();

        // Convert the in-memory raw PCM data to a valid .wav file
        var audioBytes = micBuffer.toByteArray();

        // We do the STT in the background so as not to block the UI
        contextManager.submitBackgroundTask("Transcribing Audio", () -> {
            contextManager.getIo().systemOutput("Transcribing audio");
            try {
                // Our original AudioFormat from startMicCapture
                AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, true);

                // Create an AudioInputStream wrapping the raw data + format
                try (var bais = new java.io.ByteArrayInputStream(audioBytes);
                     var ais = new AudioInputStream(bais, format, audioBytes.length / format.getFrameSize())) {
                    // Write to a temp .wav
                    var tempFile = Files.createTempFile("brokk-stt-", ".wav");
                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, tempFile.toFile());

                    // Determine which symbols to use, waiting for the future if necessary
                    Set<String> symbolsForTranscription = null;
                    if (this.customSymbolsFuture != null) {
                        try {
                            // Wait max 5 seconds for symbols to be calculated
                            Set<String> customSymbols = this.customSymbolsFuture.get(5, TimeUnit.SECONDS);
                            if (customSymbols != null && !customSymbols.isEmpty()) {
                                symbolsForTranscription = customSymbols;
                                logger.debug("Using custom symbols: {}", symbolsForTranscription);
                            } else {
                                logger.debug("Custom symbols future resolved to null or empty set.");
                            }
                        } catch (TimeoutException e) {
                            logger.warn("Timed out waiting for custom symbols future.", e);
                        } catch (ExecutionException e) {
                            logger.warn("Error executing custom symbols future.", e.getCause());
                        } catch (InterruptedException e) {
                            logger.warn("Interrupted while waiting for custom symbols future.", e);
                            Thread.currentThread().interrupt();
                        }
                    }

                    // If custom symbols weren't retrieved or were empty, fall back to context symbols
                    if (symbolsForTranscription == null) {
                        logger.debug("Falling back to context symbols for transcription.");
                        var analyzer = contextManager.getAnalyzerWrapper().getNonBlocking();
                        if (analyzer != null) {
                            var sources = contextManager.liveContext().allFragments()
                                    .flatMap(f -> f.sources().stream())
                                    .collect(Collectors.toSet());

                            // Get full symbols first
                            var fullSymbols = analyzer.getSymbols(sources);

                            // Extract short names from sources and returned symbols
                            symbolsForTranscription = sources.stream()
                                    .map(CodeUnit::shortName) // This is now Set<CodeUnit>
                                    .collect(Collectors.toSet());
                            fullSymbols.stream()
                                    .map(s -> {
                                        var parts = s.split("\\.");
                                        // Get last part as short name
                                        return parts.length > 0 ? parts[parts.length - 1] : null;
                                    })
                                    .filter(java.util.Objects::nonNull)
                                    // Add to the same set
                                    .forEach(symbolsForTranscription::add);
                            logger.debug("Using context symbols for transcription: {}", symbolsForTranscription.size());
                        }
                    }

                    // Perform transcription
                    String result;
                    var sttModel = contextManager.getService().sttModel();
                    try {
                        result = sttModel.transcribe(tempFile, symbolsForTranscription);
                    } catch (Exception e) {
                        contextManager.getIo().toolError("Error transcribing audio: " + e.getMessage());
                        result = "";
                    }
                    var transcript = result;
                    logger.debug("Successfully transcribed audio: {}", transcript);

                    // put it in the target text area
                    SwingUtilities.invokeLater(() -> {
                        if (!transcript.isBlank()) {
                            // If user typed something already, put a space
                            if (!targetTextArea.getText().isBlank()) {
                                targetTextArea.append(" ");
                            }
                            targetTextArea.append(transcript);
                        }
                    });

                    // cleanup
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignore) {
                        logger.trace("Could not delete temp STT file: {}", tempFile);
                    }
                }
            } catch (IOException ex) { // Catch specific IO errors from file writing/reading
                onError.accept("Error processing audio file: " + ex.getMessage());
            } catch (Exception ex) { // Catch broader exceptions during transcription API call etc.
                onError.accept("Error during transcription: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> targetTextArea.setEnabled(true));
            }
        });
    }
}
