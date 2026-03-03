package ai.brokk.util;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class MustacheTemplates {

    /**
     * Converts a list of strings to StringElement wrappers that support both {{.}} and {{value}}/{{first}}/{{last}}/{{index}}.
     */
    public static List<StringElement> toStringElementList(List<String> items) {
        var result = new ArrayList<StringElement>(items.size());
        int size = items.size();
        for (int i = 0; i < size; i++) {
            result.add(new StringElement(items.get(i), i, i == 0, i == size - 1));
        }
        return result;
    }

    /**
     * Wrapper for string values in Mustache templates that supports both implicit iterator {{.}}
     * (via toString()) and explicit field access ({{value}}, {{first}}, {{last}}, {{index}}).
     * This fixes the DecoratedCollection bug where {{.}} returns "Element@..." instead of the value.
     */
    public static final class StringElement {
        private final String value;
        private final int index;
        private final boolean first;
        private final boolean last;

        public StringElement(String value, int index, boolean first, boolean last) {
            this.value = value;
            this.index = index;
            this.first = first;
            this.last = last;
        }

        @SuppressWarnings("unused") // Used by Mustache reflection
        public String getValue() {
            return value;
        }

        @SuppressWarnings("unused") // Used by Mustache reflection
        public int getIndex() {
            return index;
        }

        @SuppressWarnings("unused") // Used by Mustache reflection
        public boolean isFirst() {
            return first;
        }

        @SuppressWarnings("unused") // Used by Mustache reflection
        public boolean isLast() {
            return last;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
