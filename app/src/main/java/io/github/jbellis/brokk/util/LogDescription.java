package io.github.jbellis.brokk.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Utility for producing short, log‑friendly summaries from arbitrary text
 * (human prompts, JSON blobs, stack‑traces, file paths, etc.).
 */
public final class LogDescription {

    /** Hard upper bound for a single line */
    private static final int MAX_CHARACTERS = 40;

    /** Regex for characters that add no value in a one‑line summary. */
    private static final Pattern UNWANTED = Pattern.compile("[^\\p{Alnum}\\s_.-]");

    private LogDescription() { /* utility */ }

    /**
     * Return a stable, human‑readable summary that never exceeds {@link #MAX_CHARACTERS}
     * and at most {@code maxWords} tokens.
     */
    public static String getShortDescription(String description, int maxWords) {
        if (description.isBlank()) {
            return "";
        }

        /* 1 ── basic clean‑up ***************************************************/
        // Normalise funky unicode, drop control chars except whitespace
        String cleaned = Normalizer.normalize(description, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cc}&&[^\\s]]", "");

        // Strip punctuation we decided is “junk”
        cleaned = UNWANTED.matcher(cleaned).replaceAll(" ");

        // Compress all newlines / tabs to single spaces and squeeze multiple spaces
        cleaned = cleaned.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        if (cleaned.isEmpty()) {
            return "";
        }

        /* 2 ── word‑level truncation ********************************************/
        String[] tokens = cleaned.split("\\s+");
        if (tokens.length > maxWords) {
            cleaned = String.join(" ", Arrays.copyOf(tokens, maxWords));
        }

        /* 3 ── character‑level truncation **************************************/
        if (cleaned.length() > MAX_CHARACTERS) {
            // Cut at the last word boundary inside the limit if we can
            int cut = cleaned.lastIndexOf(' ', MAX_CHARACTERS);
            if (cut < 0 || cut < MAX_CHARACTERS * 0.4) {   // no sensible break → hard cut
                cut = MAX_CHARACTERS;
            }
            cleaned = cleaned.substring(0, cut).trim();
        }

        return cleaned;
    }

    /** Convenience overload – 8 words has proven a sweet spot for our logs. */
    public static String getShortDescription(String description) {
        return getShortDescription(description, 7);
    }
}
