package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IConsoleIO;

/**
 * For analyzers that give feedback to users. Examples of useful feedback could be to flag limited analysis capabilities
 * until the build is fixed.
 */
public interface CanCommunicate {

    /**
     * Sets the {@link IConsoleIO} object that the analyzer can use to send user feedback.
     *
     * @param io the IO object to use.
     */
    void setIo(IConsoleIO io);
}
