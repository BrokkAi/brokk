
package io.github.jbellis.brokk.difftool.search;

import java.util.Objects;

public class SearchHit {
    int line;
    int fromOffset;
    int toOffset;
    int size;

    public SearchHit(int line, int offset, int size) {
        this.line = line;
        this.fromOffset = offset;
        this.size = size;
        this.toOffset = offset + size;
    }

    public int getLine() {
        return line;
    }

    public int getFromOffset() {
        return fromOffset;
    }

    public int getSize() {
        return size;
    }

    public int getToOffset() {
        return toOffset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SearchHit sh)) {
            return false;
        }
        return fromOffset == sh.fromOffset && toOffset == sh.toOffset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromOffset, toOffset);
    }
}
