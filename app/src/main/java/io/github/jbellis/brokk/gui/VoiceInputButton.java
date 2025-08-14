package io.github.jbellis.brokk.gui;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** A button that captures voice input from the microphone and transcribes it to a text area. */
public class VoiceInputButton extends JButton {
    private static final Logger logger = LogManager.getLogger(VoiceInputButton.class);

    private final JTextArea targetTextArea;
    private final ContextManager contextManager;
    private final Consumer<String> onError;
    private final Runnable onRecordingStart;
    private final @Nullable Future<Set<String>> customSymbolsFuture;

    // For STT (mic) usage
    private volatile @Nullable TargetDataLine micLine = null;
    private final ByteArrayOutputStream micBuffer = new ByteArrayOutputStream();

    @Nullable
    private volatile Thread micCaptureThread = null;

    private @Nullable ImageIcon micOnIcon;
    private @Nullable ImageIcon micOffIcon;

    /**
     * Creates a new voice input button.
     *
     * @param targetTextArea the text area where transcribed text will be placed
     * @param contextManager the context manager for speech-to-text processing
     * @param onRecordingStart callback when recording starts
     * @param customSymbolsFuture Optional Future providing a set of symbols to prioritize for transcription hints. Can
     *     be null.
     * @param onError callback for error handling
     */
    public VoiceInputButton(
            JTextArea targetTextArea,
            ContextManager contextManager,
            Runnable onRecordingStart,
            @Nullable Future<Set<String>> customSymbolsFuture,
            Consumer<String> onError) {
        this.targetTextArea = targetTextArea;
        this.contextManager = contextManager;
        this.onRecordingStart = onRecordingStart;
        this.onError = onError;
        this.customSymbolsFuture = customSymbolsFuture;

        // Determine standard button height to make this button square
        var referenceButton = new JButton(" ");
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
            micOnIcon = new ImageIcon(requireNonNull(getClass().getResource("/mic-on.png")));
            micOffIcon = new ImageIcon(requireNonNull(getClass().getResource("/mic-off.png")));
            // Scale icons to fit the dynamic button size
            micOnIcon = new ImageIcon(
                    micOnIcon.getImage().getScaledInstance(iconDisplaySize, iconDisplaySize, Image.SCALE_SMOOTH));
            micOffIcon = new ImageIcon(
                    micOffIcon.getImage().getScaledInstance(iconDisplaySize, iconDisplaySize, Image.SCALE_SMOOTH));
            logger.trace("Successfully loaded and scaled mic icons to {}x{}", iconDisplaySize, iconDisplaySize);
        } catch (Exception e) {
            logger.warn("Failed to load mic icons", e);
            // We'll fall back to text if icons can't be loaded
            micOnIcon = null;
            micOffIcon = null;
        }

        // Set default appearance
        if (micOffIcon == null) {
            setText("Mic");
        } else {
            setIcon(micOffIcon);
        }

        Dimension buttonSize = new Dimension(normalButtonHeight, normalButtonHeight);
        setPreferredSize(buttonSize);
        setMinimumSize(buttonSize);
        setMaximumSize(buttonSize);
        setMargin(new Insets(0, 0, 0, 0));

        // Track recording state
        putClientProperty("isRecording", false);

        // Set tooltip for keyboard shortcut discoverability
        setToolTipText("Toggle Microphone (Cmd/Ctrl+L)");

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
        model.setEnabled(true);
    }

    /**
     * Convenience constructor without custom symbols.
     *
     * @param targetTextArea the text area where transcribed text will be placed
     * @param contextManager the context manager for speech-to-text processing
     * @param onRecordingStart callback when recording starts
     * @param onError callback for error handling
     */
    public VoiceInputButton(
            JTextArea targetTextArea,
            ContextManager contextManager,
            Runnable onRecordingStart,
            Consumer<String> onError) {
        this(targetTextArea, contextManager, onRecordingStart, null, onError);
    }

    /** Starts capturing audio from the default microphone to micBuffer on a background thread. */
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
            micLine = (TargetDataLine) requireNonNull(AudioSystem.getLine(info));
            micLine.open(format);
            micLine.start();

            // micBuffer is now final and initialized in constructor, reset it here
            synchronized (micBuffer) {
                micBuffer.reset();
            }
            micCaptureThread = new Thread(
                    () -> {
                        var data = new byte[4096];
                        while (micLine != null && micLine.isOpen()) {
                            int bytesRead = micLine.read(data, 0, data.length);
                            if (bytesRead > 0) {
                                synchronized (micBuffer) {
                                    micBuffer.write(data, 0, bytesRead);
                                }
                            }
                        }
                    },
                    "mic-capture-thread");
            micCaptureThread.start();
            // Notify that recording has started
            onRecordingStart.run();
        } catch (Exception ex) {
            logger.error("Failed to start mic capture", ex);
            onError.accept("Error starting mic capture: " + ex.getMessage());
            targetTextArea.setEnabled(true);
        }
    }

    /** Stops capturing and sends to STT on a background thread. */
    private void stopMicCaptureAndTranscribe() {
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
                            var sources = contextManager
                                    .liveContext()
                                    .allFragments()
                                    .flatMap(f -> f.sources().stream())
                                    .collect(Collectors.toSet());

                            // Get full symbols first
                            var fullSymbols = analyzer.getSymbols(sources);

                            // Extract short names from sources and returned symbols
                            final Set<String> tempSymbols = new HashSet<>(
                                    sources.stream().map(CodeUnit::shortName).collect(Collectors.toSet()));
                            fullSymbols.stream()
                                    .map(s -> {
                                        List<String> parts = Splitter.on('.').splitToList(s);
                                        return !parts.isEmpty() ? parts.getLast() : null;
                                    })
                                    .filter(java.util.Objects::nonNull)
                                    .forEach(tempSymbols::add); // Add to the effectively final temporary set
                            symbolsForTranscription = tempSymbols; // Assign to the field
                            logger.debug("Using context symbols for transcription: {}", symbolsForTranscription.size());
                        }
                    }

                    // Perform transcription
                    String result;
                    var sttModel = contextManager.getService().sttModel();
                    Set<String> finalSymbolsForTranscription =
                            symbolsForTranscription != null ? symbolsForTranscription : Collections.emptySet();
                    try {
                        result = sttModel.transcribe(tempFile, finalSymbolsForTranscription);
                    } catch (Exception e) {
                        IConsoleIO iConsoleIO = contextManager.getIo();
                        iConsoleIO.toolError("Error transcribing audio: " + e.getMessage(), "Error");
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
            } catch (IOException ex) {
                onError.accept("Error processing audio file: " + ex.getMessage());
            } catch (Exception ex) {
                onError.accept("Error during transcription: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> targetTextArea.setEnabled(true));
            }
        });
    }
}
