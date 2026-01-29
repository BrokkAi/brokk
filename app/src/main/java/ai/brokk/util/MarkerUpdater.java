package ai.brokk.util;

import java.util.regex.Pattern;

/**
 * Utility for replacing content between specific BEGIN/END markers (HTML comments).
 */
public final class MarkerUpdater {

    private final String beginMarker;
    private final String endMarker;

    public MarkerUpdater(String sectionName) {
        this.beginMarker = "<!-- BROKK " + sectionName + " BEGIN -->";
        this.endMarker = "<!-- BROKK " + sectionName + " END -->";
    }

    /**
     * Replaces the content between markers in the source string.
     * If markers don't exist, appends the new section to the end.
     */
    public String update(String source, String newContent) {
        String wrapped = beginMarker + "\n" + newContent.strip() + "\n" + endMarker;

        Pattern pattern = Pattern.compile(
                Pattern.quote(beginMarker) + ".*?" + Pattern.quote(endMarker),
                Pattern.DOTALL
        );

        var matcher = pattern.matcher(source);
        if (matcher.find()) {
            return matcher.replaceFirst(wrapped);
        }

        // Not found, append with double newline if source is not empty
        if (source.isBlank()) {
            return wrapped;
        }
        return source.stripTrailing() + "\n\n" + wrapped;
    }
}
